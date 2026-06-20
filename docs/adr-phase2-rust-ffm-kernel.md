# ADR: Productize the Phase 2 Rust radix kernel (Panama FFM)

**Status:** Proposed
**Date:** 2026-06-20
**Deciders:** Richmond (project owner)
**Related:** `phase2-ffm/` (the proven proof-of-concept), `docs/HANDOFF.md` (Phase 2 roadmap), `docs/ARCHITECTURE.md` §5.1 (the `SortBuffer` off-heap seam), `strategy/RadixSortStrategy.java` + `strategy/RadixPlan.java` (the Java fallback), `registry/StrategyProvider` (the SPI seam), `core/StrategyCapabilities` (the `Runtime` capability)

---

## Context

`phase2-ffm/` proves the **hard, risky part** of Phase 2 end-to-end: the JVM hands an **off-heap
`MemorySegment`** to a **Rust LSD radix sort** through the **Panama Foreign Function & Memory API** (a
downcall) and copies the result back. The slice is differential-tested against `Arrays.sort` (300 random
arrays including negatives and `Long.MIN/MAX`, 0 mismatches), with its own Rust unit tests, bootstrapped on
JDK 21 + rustc 1.75. So JVM↔native interop with zero-copy off-heap data **works**; what remains is turning a
standalone `main()` into a shipping `SortStrategy` without compromising the "runs everywhere" property of the
jar.

Three hard constraints shape every option below:

1. **Toolchain split.** The main build targets **Java 17** (`release = 17`); the FFM API is finalized only in
   **JDK 22** (JEP 454; preview in 21). So the FFM glue **cannot be compiled by, or loaded on, a JDK 17
   JVM** — `java.lang.foreign` isn't there. Any integration must isolate the 22-only code so the 17 build and
   17 runtime are unaffected.
2. **Fallback is mandatory.** The jar must still sort on a 17 JVM, on a platform with no prebuilt `cdylib`, or
   when `--enable-native-access` is withheld. The existing pure-Java `radix.lsd` is that fallback, and the
   capability seam (`StrategyProvider` SPI + capability-gated registry) was built for exactly this.
3. **The Java baseline moved.** Since the PoC, `RadixSortStrategy` became **entropy-aware** (`RadixPlan`:
   sign-flip → **offset-by-min** → adaptive bits-per-pass/pass-count). The PoC kernel is a **fixed 8-bit/pass**
   radix over absolute magnitude — *weaker* than today's Java path on narrow-band/high-magnitude keys. The
   native kernel must match that plan, or the FFM marshaling cost will not be earned back.

The guiding metric for Phase 2 is unchanged: **a native pick must beat the Java radix on `SortResult`
(comparisons+moves are zero for both — so wall-clock at scale) by more than the FFM marshaling cost, or it
is not selected.** Same "measure before defaulting" discipline as the Phase 3 `EnsembleFeedBenchmark`.

---

## Decision

Productize the kernel as a **separate, optional Gradle module `sbs-kernels-rust`** that compiles its FFM glue
with a **JDK 22 toolchain** and registers a `radix.lsd.rust` strategy through the existing **`StrategyProvider`
SPI**, discovered at runtime only when (a) the module jar is present, (b) the JVM is 22+, (c) the platform
`cdylib` loads, and (d) native access is permitted. When any of those fail, the strategy simply isn't in the
registry and selection falls back to the pure-Java `radix.lsd` — no caller change, the jar runs everywhere.
The native kernel is brought to **parity with `RadixPlan`** (offset-by-min + adaptive passes) and gated
behind a **JMH-measured** size threshold so it is chosen only where it actually wins.

This keeps the main module on 17 with **zero FFM knowledge**, honoring the project's "swap any piece without
touching the others" philosophy: the kernel is one more `StrategyProvider`, not a change to the engine.

---

## Options Considered

The decision is really about **how the Java-22 FFM glue coexists with the Java-17 build/runtime.** Three
mechanisms:

### Option A — Separate optional module + SPI discovery *(recommended)*

A standalone `sbs-kernels-rust` module: its own Java 22 toolchain, a Gradle task that shells `cargo`/`rustc`
to produce the platform `cdylib` and packages it on the resource path, and its own
`META-INF/services/…StrategyProvider` that contributes `radix.lsd.rust`. The main app composes it as an
*optional runtime dependency*.

| Dimension | Assessment |
|---|---|
| Complexity | Med — new module + cargo task + a resilient SPI load guard |
| Build isolation | **High** — main module stays 17, never sees `java.lang.foreign` |
| Fallback | **Natural** — module absent ⇒ strategy absent ⇒ Java radix; matches the existing capability seam |
| Familiarity | High — `StrategyProvider` SPI is already *the* extension point (`BuiltinStrategyProvider`) |

**Pros:** cleanest separation; the kernel owns its toolchain, native packaging, and registration; the engine
is untouched; aligns with the README's stated plan and the SPI design. **Cons:** consumers must add the
kernel jar; the host's `ServiceLoader` iteration must **catch `ServiceConfigurationError`** so a 22-compiled
provider on a 17 JVM (or a missing `cdylib`) degrades silently instead of throwing.

### Option B — Multi-release JAR (MRJAR)

One jar; the FFM strategy lives in a `META-INF/versions/22` overlay, so a 22+ JVM loads it and a 17 JVM sees
only the base classes.

| Dimension | Assessment |
|---|---|
| Complexity | Med-High — two source sets, MRJAR Gradle wiring, version-gated `StrategyProvider` |
| Build isolation | Med — 22 source set in the same module; toolchain juggling |
| Fallback | Automatic by JVM version, but **adding** (not replacing) a strategy via overlay is awkward |
| Familiarity | Low — MRJAR is rarely-trodden; subtle classloading rules |

**Pros:** a single artifact that "just works" per JVM version; no extra dependency for consumers. **Cons:**
MRJAR overlays *replace* classes rather than add them, so exposing a *new* strategy means version-gating the
`StrategyProvider` itself (the base lists Java strategies, the 22 overlay lists those + `radix.lsd.rust`) —
easy to get wrong; native-resource packaging per platform still needed; harder to test the two variants.

### Option C — Reflection / MethodHandles from 17 code

Keep all code in the 17 module and invoke `java.lang.foreign` reflectively so there is no compile-time 22
dependency.

**Pros:** one module, one toolchain. **Cons:** abandons compile-time type safety for the most
memory-sensitive code in the system; verbose and fragile; still needs a 22 *runtime*; no real isolation win
over Option A. **Rejected.**

---

## Trade-off Analysis

Option A wins on the axis that matters most here — **isolation and fallback**. The project's whole structure
is pluggable providers behind stable seams; a native kernel is the textbook case for "a new
`StrategyProvider` that may or may not be present," and the capability-gated registry already degrades
gracefully when a strategy is absent. MRJAR (B) collapses everything into one artifact, which is nice for
distribution, but it buys that at the cost of version-gating the provider list and walking MRJAR's sharp
edges — and it still doesn't avoid the real work (cargo task, per-platform natives, the kernel itself). C
trades the one thing you don't want to trade in FFM code (type safety) for no isolation benefit.

The native kernel will **not** beat Java for free: the Java radix is already O(n) with an entropy-aware plan,
and FFM adds fixed marshaling (off-heap alloc + two O(n) copies). The native win has to come from (i) a
tighter inner loop and (ii) eventually **rayon parallelism** — and only at large `n`. Hence the kernel must
adopt `RadixPlan`'s offset-by-min/adaptive schedule (constraint 3) and be **benchmarked before** the
cost model or selector is allowed to prefer it. Until then `radix.lsd.rust` ships as an *available but
not-default* arm (explicit opt-in / large-`n` gate), mirroring how WikiSort is offered only where it pays.

---

## Consequences

**Easier:**
- A real native acceleration path behind the existing seam, with a guaranteed pure-Java fallback — the jar
  still runs on any JDK 17+ and any platform.
- The `StrategyCapabilities.Runtime.RUST` field (already in the model, currently always `JAVA`) finally has a
  user, and the differential test harness already validates any new strategy against the JDK reference, so
  correctness of the native path is covered the moment it registers.

**Harder / to revisit:**
- **Build & CI** grow a native dimension: a `cargo`/`rustc` step, a **per-platform `cdylib`** (Linux `.so`,
  macOS `.dylib`, Windows `.dll`), and a CI matrix to build/test them. Distribution must decide between
  packaging prebuilt natives (resource classifiers) vs building on install.
- **Two implementations** of radix to keep in lockstep (mitigated: `DifferentialTest` + a shared
  signed→unsigned mapping already proven in the PoC).
- **Native trust surface:** memory-unsafety risk is confined to Rust's one `unsafe` slice-from-raw-parts
  (bounded by the passed `len`); the JVM side uses a confined `Arena` (try-with-resources) and
  `--enable-native-access` gating. These must be kept as invariants.
- Selector/cost-model integration is **deferred until measured** — a non-trivial follow-on (a size-gated
  `radix.lsd.rust` cost arm), not part of landing the strategy.

**Explicitly out of scope:** the off-heap `SortBuffer` variant (ARCHITECTURE §5.1) — worthwhile so large
runs never touch the heap, but an optimization on top of a working integration, sequenced after the kernel
registers and benchmarks favorably; and rayon parallelism, which comes only after the single-threaded native
path is integrated and measured.

---

## Action Items

1. [ ] **Module skeleton.** Add `sbs-kernels-rust` (Gradle, Java **22** toolchain via `JavaLanguageVersion`;
   the root build stays 17). Move `phase2-ffm/SbsRadixFfm.java` in as the FFM bridge class.
2. [ ] **Native build task.** A Gradle task that runs `cargo`/`rustc` to produce the platform `cdylib` and
   places it on the module's resource path; wire it before `processResources`/test.
3. [ ] **Kernel parity.** Port `RadixPlan`'s **offset-by-min + adaptive bits-per-pass/pass-count** into
   `radix.rs` (today it is fixed 8-bit/pass over absolute magnitude), so the native path is competitive on
   narrow-band/high-magnitude keys.
4. [ ] **`radix.lsd.rust` strategy + SPI.** A `SortStrategy` with `StrategyCapabilities.backingRuntime = RUST`
   and a **guarded static load** (JDK ≥ 22, `cdylib` present, native access granted) that throws/declines so
   the provider registers it only when usable; contribute it via a module-local `StrategyProvider`. The host's
   `ServiceLoader` use must **catch `ServiceConfigurationError`** so a missing/incompatible module never breaks
   discovery.
5. [ ] **Differential coverage.** Add `radix.lsd.rust` to `DifferentialTest`'s strategy list (only when
   registered) so it is validated against the JDK reference across all pathological + duplicate shapes.
6. [ ] **JMH benchmark.** Native vs `radix.lsd` across sizes/distributions (extend `SortStrategyBenchmark`),
   to find the `n` where the native path earns back marshaling. **Do not** let the cost model/selector prefer
   it until this exists.
7. [ ] **(Then) selector integration** — a size-gated native radix arm — and **(later) rayon parallelism**;
   **(separately) the off-heap `SortBuffer`** variant.

**Done-well metric:** on a 22+ JVM with the kernel present, `radix.lsd.rust` matches the JDK reference on
every shape **and** beats `radix.lsd` in JMH wall-clock above the measured size threshold; on a 17 JVM or
without the native lib, selection is byte-for-byte the Java path it is today.
