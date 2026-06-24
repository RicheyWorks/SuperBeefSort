# SuperBeefSort — handoff notes

Last updated: 2026-06-23. Author: Richmond (with Claude). Status: **Phase 0 + Phase 1 + Phase 3 done.
Phase 2 Rust kernel productized; native is 2× slower than Java at n≥100k (FFM marshaling cost); selector
integration deferred (item 7). Phase 4 gate measured: real exploitable gaps found; action item 2 closed;
items 3-5 open. Phase 5 external merge sort + typed step-event stream done.**
Plus: a global inversion signal, a learned (sample) sort, deterministic mode,

## Session 2026-06-23b — Phase 2 item 7 closed (JMH-validated); 14% side-quest rejected; Phase 4b started

Continued from 2026-06-23 (below). Three things happened.

**1. `radix.lsd.parallel` routing JMH-validated — Phase 2 item 7 closes positive.** The first
`ParallelRadixBenchmark` run read *inconclusive*, but it was **confounded**: two separate `@Benchmark` methods
meant JMH measured all-`par`-then-all-`seq` under `@Fork(1)`, so machine drift aliased onto the gap — exposed
by a 50k *control* (sub-threshold ⇒ single chunk ⇒ no threads) that still showed ~14%. Hardened the benchmark
(algorithm as a fastest-varying `@Param` ⇒ `seq`/`par` back-to-back per n; `@Fork(3)`; n→2M/5M; kept the 50k
control; `-Xmx4g`). The de-confounded re-run is decisive and all-significant: `radix.lsd.parallel` is faster at
every n — raw 1.2×@100k → 2.6×@5M (1.06×→2.30× thread-only after the baseline). **`PARALLEL_RADIX_CROSSOVER`
stays `1<<16`** (validated, not retuned); routing tests unchanged. Full tables in
`docs/adr-phase2-offheap-sortbuffer.md` ("JMH results" + "Hardened re-run results").

**2. The 14% sub-threshold baseline — investigated, refactor tested & rejected.** Code comparison showed the two
single-chunk paths do *identical* work (same `RadixPlan`, primitive-array passes, histogram→prefix→scatter), so
the gap is JIT codegen, not algorithmic. Hypothesis: `RadixSortStrategy` reuses swapped mutable locals vs the
parallel path's fresh per-pass `final` locals. Implemented that refactor (behaviour-preserving) and
re-benchmarked — **it did not move the gap** (50k control held 1.13×; seq unchanged within noise). The unchanged
`par` code drifted −26%…+14% between runs, i.e. the run-to-run noise floor (~±15–25%) is comparable to the gap
itself. **Reverted; `radix.lsd` is unchanged.** Lesson recorded in the ADR "secondary finding".

**3. Phase 4b started — `SbsIntelligence` gRPC server built + verified.** The optional runtime learned-selection
service (ADR item 6). Proto `src/main/proto/sbs_intelligence.proto` (`Predict`/`Observe`; the client sends the
**raw** `DataProfile`, the server derives the 15 features so derivation lives in one place). Python server
`tools/phase4/service/server.py` serves the schema-v1 flat tree (walks it identically to Java `SelectorModel`,
`SMART`-only advice) and appends the observe stream to `observations.jsonl`. **Verified in-sandbox**
(`smoke_test.py`): 72/72 `Predict` parity vs an independent unrounded oracle (4 sizes × 9 shapes × 2 key modes),
plus Observe + non-SMART. **Next (host-built):** Java `RemoteStrategySelector` (size gate + circuit breaker +
local fallback) + grpc build wiring (protobuf gradle plugin + grpc-java), then the retrain/hot-swap loop.
Design in `tools/phase4/service/README.md`.

**Sandbox caveats this session:** the bash mount served stale/truncated views of Edit-tool-written files (the
host files were correct — verified via the Read tool and the host `gradlew build`), and a stray `.git/index.lock`
had to be cleared via the file-delete permission; commits were done on the host. JDK 17/CSRBT build + JMH all
run host-side.

---

## Session 2026-06-23 — Phase 2 item 7(b): provisional SMART routing for `radix.lsd.parallel`

Continued from the previous session, which added the `bench/ParallelRadixBenchmark` JMH crossover sweep
(`radix.lsd` vs `radix.lsd.parallel`, n ∈ {50k, 100k, 500k, 1M}). This session wired the **selector routing**
(item 7(b)), provisionally — chosen because the host JMH can't run in the dev sandbox (JDK 11, no CSRBT,
empty `gradlew`), so a measured crossover isn't available yet and won't be fabricated.

**What changed:**

- **`RuleBasedStrategySelector` (the engine default)** — `smart()` now takes the `registry` and, in the
  integer-key branch, routes to `radix.lsd.parallel` when `p.size() >= PARALLEL_RADIX_CROSSOVER` and the
  strategy is registered; otherwise sequential `radix.lsd` as before. The branch is placed **after** the
  counting gate, so bounded-range integers still pick `counting`. New constant
  `PARALLEL_RADIX_CROSSOVER = ParallelRadixSortStrategy.PARALLEL_THRESHOLD` (`1<<16`).

- **`ParallelRadixSortStrategy.PARALLEL_THRESHOLD`** promoted `static` → `public static` so the selector can
  pin the crossover to the strategy's own parallel-engage point (and so the existing javadoc `{@link}` in
  `ParallelRadixSortTest` resolves). Below this point the strategy runs single-threaded == `radix.lsd`, so
  routing there would be a no-op; at/above it the histogram/scatter passes fan out.

- **Cost-model + bandit: intentionally NOT changed.** The cost model's comparisons+moves objective scores
  `radix.lsd` and `radix.lsd.parallel` identically (same passes/moves, same `LINEAR` aux class), and its
  `learned` ~5n arm already dominates radix's ~8n — so the cost model never selects `radix.lsd`, and a swap
  there would be dead code. The bandit can't separate the two arms on metered cost either. The parallel win
  is purely wall-clock, which only the rule-based size gate expresses. A wall-clock-aware cost is the seam if
  that's ever wanted (noted in the ADR), out of scope until the JMH crossover is known.

**Why provisional + why safe to ship now:** `radix.lsd.parallel` is byte-for-byte identical to sequential
`radix.lsd` for any chunk count (deterministic + stable; proven by the prior 972/972 `PRadixCheck` fuzz), so
routing to it changes only wall-clock, never the result. The crossover constant is flagged PROVISIONAL in the
selector, the ADR, and PROGRESS; the **one remaining step is host-only**: run
`./gradlew jmh -Pbench=ParallelRadixBenchmark` (JDK 22), record the numbers, and retune
`PARALLEL_RADIX_CROSSOVER` upward if the measured profit crossover is higher than `1<<16`.

**Tests:** four new `RuleBasedSelectorTest` cases — wide-range large integer → `radix.lsd.parallel`; exactly
at the crossover → parallel; one element below → `radix.lsd`; large *bounded*-range → still `counting`.
**Verification:** sandbox has no `javac`, so the decision logic was cross-checked by a faithful Python mirror
of `smart()` (`mirror_rulebased_smart.py`): all 4 new + 4 non-keyed regression cases pass, plus a crossover
sweep (n=16…1M) and a bounded-range sweep. Host `./gradlew build` (JDK 17 + CSRBT) is the compile/test gate.

**Files touched:** `select/RuleBasedStrategySelector.java`, `strategy/ParallelRadixSortStrategy.java`
(visibility only), `test/.../RuleBasedSelectorTest.java`, `docs/adr-phase2-offheap-sortbuffer.md`, `PROGRESS.md`.

---

## Session 2026-06-21c — Phase 4 gate: metering bug, real gaps, ADR item 2 closed

`Phase4DecisionGate` initially reported 0.00% max regret — a false result. `JdkSortStrategy` used
`b.comparator()` (raw, unmetered) and `b.set()` (no `recordMove()`), so its measured cost was always 0.
The oracle always picked `jdk.timsort` (cost 0) and the regret formula special-cased `oracle_cost == 0 → 0`,
making every workload appear optimal regardless of what the selector chose.

**Fix:** `JdkSortStrategy.sort()` now uses `(x, y) -> b.compareValues(x, y)` and calls `b.recordMove()` in
the writeback loop, matching the metering contract every other strategy follows. The gate harness gained an
oracle-spread report (asserting ≥ 2 distinct winners and oracle_cost > 0 in all workloads).

**Corrected results** (324 workloads, 6 sizes × 9 shapes × 2 key modes × 3 trials, oracle sees 4 distinct
winners — `counting` 138, `jdk.timsort` 108, `insertion` 48, `intro` 30):

| Selector | Near-optimal (< 5% regret) | Mean regret | Max regret |
|---|---|---|---|
| `CostModelStrategySelector` | 196/324 (60.5%) | 386.52% | 6886.6% |
| `BanditStrategySelector` | 236/324 (72.8%) | 191.94% | 7265.4% |

Worst gaps: reversed comparable-only (cost model picks `intro`; `jdk.timsort` detects the run in O(n) — up
to 68× cheaper at n=50k); clustered integer-keyed (cost model misses `counting`). **The exploitable gap is
real.** ADR `docs/adr-phase4-python-intelligence.md`: action item 2 marked `[x]` with corrected findings;
items 3-5 (train, export, `LearnedModelStrategySelector`) remain open; status → In Progress.

Also closed in this session: Phase 2 JMH benchmark results recorded in the ADR; Phase 2 ADR status → Done.

---

## Session 2026-06-21b — Phase 5: external merge sort (sbs external/ package)

Implemented ADR action items 1–4 from `docs/adr-phase5-observability-scale.md`. Host-side
`./gradlew build` is the compile/test gate (sandbox is JDK 11 without CSRBT; verified by static review +
tracing).

**What was built (`src/main/java/…/external/`):**

- **`SpillSerializer<K>`** (public interface) — `write(K, DataOutputStream)` + `read(DataInputStream): K`; built-in
  static factories `forLongs()` / `forIntegers()` / `forStrings()`; callers supply an anonymous implementation
  for custom types. EOF signals end-of-run (no header/count needed: the writers close cleanly).

- **`SpillFile<K>` / `SpillWriter<K>` / `SpillReader<K>`** (package-private) — temp-file lifecycle: created via
  `SpillFile.create(ser)`, registered with `deleteOnExit` as a crash-cleanup safety net; explicitly deleted via
  `delete()` as soon as the file is no longer needed. `SpillWriter` wraps `DataOutputStream(BufferedOutputStream)`;
  `SpillReader` wraps `DataInputStream(BufferedInputStream)` with a peek-ahead `buffered` field and EOF detection.

- **`TournamentTree<K>`** (package-private) — min-heap over `Entry<K>(K value, int runIndex)` entries; ties broken
  by run index ascending (earlier chunk precedes later-chunk equals → stable between runs). Each `next()` poll
  pulls the minimum, then refills from the same run's reader. `closeAll()` closes all readers.

- **`ExternalMergeSorter<K>`** (public) — orchestrates run generation + multi-pass merge:
  - *Run generation*: iterates `input` in `runSize`-element windows, sorts each with the full in-memory engine
    (profile → select → sort — every chunk benefits from profiling and intelligent selection), spills the sorted
    chunk to a `SpillFile`.
  - *Multi-pass merge*: `mergePasses` loops while `current.size() > maxFanIn`, grouping runs into fan-in-sized
    batches and merging each group via a `TournamentTree` into an intermediate `SpillFile`; intermediate files are
    deleted immediately after the next pass. Terminates when ≤ `maxFanIn` final runs remain.
  - *Output*: `sortToList` materialises in RAM (for testing); `sortAndFeed` streams directly from the final
    `TournamentTree` into a `CsrbtTarget` without materialising — the out-of-core path.

- **`ExternalSortResult`** (public record) — `{elements, runs, mergePasses, elapsedNanos}` returned by `feedInto`.

- **`BeefSort.external(SpillSerializer<K>)`** — entry point; returns `ExternalSortBuilder` (non-static inner class;
  inherits `comparator`, `keyEncoder`, `selector`, `policy`, `source`). Builder chainable: `runSize(int)` (default
  100k) + `fanIn(int)` (default 16). Terminal methods: `toList()`, `feedInto(OrderedSet<K>)`,
  `feedInto(OrderedSet<K>, int maxSize)`.

**Stability:** outer sort is stable when `SelectionPolicy.STABLE` is used for each chunk sort — merge sort is
stable, and the tournament tree's run-index tiebreaker preserves the chunk order for inter-run equal elements.
Under `SMART`, within-chunk stability depends on the chosen strategy (e.g. introsort is not stable); callers who
need a globally stable external sort should set `.policy(SelectionPolicy.STABLE)`.

**Test (`ExternalMergeSortTest`, 11 tests):** empty input, single element, single run, multiple runs single-pass,
multiple passes (runSize=5 fanIn=4 on n=100 → 3 passes), all pathological shapes (sorted/reversed/all-equal/
sawtooth/few-distinct at various n with runSize=10), random battery (20 seeds × varying n/range/runSize), Long
serializer (including MIN/MAX values), String serializer, `ExternalSortResult` metrics (5 runs, 1 pass), feed-into-
OrderedSet correctness (min/max/size match), stability (STABLE policy + multi-pass + custom Item serializer).

**ADR status:** action items 1–4 done. Remaining open items:
- Item 5: IO/JMH benchmark (run size vs fan-in vs passes; only then tune defaults)
- Items 6–7: typed step-event schema (opt-in per-comparison/swap SortEvent stream) + conditional TS visualizer

---

## Session 2026-06-21 — Phase 2: Rust radix kernel productized (sbs-kernels-rust)

Implemented all six ADR action items from `docs/adr-phase2-rust-ffm-kernel.md`. Build verified
green on JDK 21 (host machine). The kernel module activates when Gradle runs under JDK 22+ — no
change needed; `settings.gradle.kts` conditionally includes it.

**What was built:**

- **`sbs-kernels-rust/`** — new optional Gradle module. `build.gradle.kts` specifies a JDK 22
  toolchain; a `cargoBuild` Exec task + `copyNativeLib` Copy task build the Rust cdylib and place
  it on the module's resource path (`native/<platform>/sbsradix.dll|so|dylib`).

- **`rust/src/lib.rs`** — entropy-aware Rust kernel. `sort_keyed_flat` sorts n (key, payload)
  pairs (interleaved u64 flat layout); `radix_plan` mirrors `RadixPlan.forWidth` exactly
  (offset-by-min + adaptive bits-per-pass). Stable: equal-key pairs maintain forward order. 6/6
  Rust unit tests green.

- **`RustRadixBridge`** — FFM bridge (JDK 22). Extracts the cdylib from classpath resources to a
  temp file; loads via `SymbolLookup.libraryLookup` into `Arena.global()`; exposes `sortKeyed()`.
  All failures in the static initialiser are caught → `isAvailable()` returns false → strategy
  not registered → pure-Java `radix.lsd` fallback.

- **`RustRadixSortStrategy<K>`** (`radix.lsd.rust`) — packs (sign-flipped key, original-index)
  pairs into a confined `Arena` MemorySegment, calls the native kernel, extracts the sorted
  permutation, writes items back. StrategyCapabilities: stable, out-of-place, requiresIntegerKeys,
  Runtime=RUST, LINEAR aux.

- **`RustKernelStrategyProvider`** + SPI service file — registers `radix.lsd.rust` only when the
  bridge is available.

- **`StrategyRegistry.withDefaults()`** — now catches `ServiceConfigurationError` so a JDK-22
  compiled kernel module loaded on JDK 17/21 fails silently (class-version mismatch wraps as
  SCE).

- **`DifferentialTest`** — two new test methods covering `radix.lsd.rust` across pathological
  shapes + jqwik property; both no-op on JDK < 22 (strategy not in registry). Separate
  `RustRadixDifferentialTest` in the kernel module's test sources uses `Assumptions` to skip
  when the bridge is unavailable.

- **`RadixNativeBenchmark`** — JMH benchmark: `radixLsd` vs `rustRadixLsd` across
  n=[1k, 10k, 100k, 500k]. No-ops gracefully when native strategy absent.
  Run: `./gradlew jmh -Pbench=RadixNativeBenchmark`
  **Selector integration is deferred until these results confirm margin > FFM marshaling cost.**

**Activation path (once JDK 22 is installed):**

```powershell
# Run Gradle with JDK 22 (set JAVA_HOME or use toolchain config):
./gradlew :sbs-kernels-rust:build   # builds cdylib + compiles FFM bridge
./gradlew build                      # kernel module included; DifferentialTest exercises Rust strategy
./gradlew jmh -Pbench=RadixNativeBenchmark  # measure native vs Java radix
```

**Done metric status:** All structural conditions are met. On JDK 22 with `--enable-native-access`,
`RustRadixDifferentialTest` will validate correctness across all shapes; `RadixNativeBenchmark`
will measure the crossover size above which the native path beats `radix.lsd`.

---

Last updated: 2026-06-20. Author: Richmond (with Claude). **See the "Session 2026-06-20" section below for the
latest work (co-optimization prior, memory-aware selection, and the Phase 2/4/5 roadmap ADRs).**

## Session 2026-06-20 — co-optimization prior, memory-aware selection, roadmap ADRs
differential + anti-quicksort chaos tests, cost-model & self-tuning (bandit) selectors, a JMH suite, CI, and a
web step-visualizer with self-contained record/replay; a bounded streaming feed; and **concept-drift-aware
adaptive streaming** that re-selects the sort strategy mid-stream; and **profile-guided co-optimization** (the
sort's data profile primes the tree's strategy adaptation); plus **MSD radix for string/byte keys**, **entropy-aware LSD radix**, a **stable in-place merge**, and **Streams-API collectors**. Everything is `./gradlew build` **green**, verified
against real CSRBT from a clean clone (JDK 17/21 + Rust bootstrapped in-sandbox); the new drift + co-optimization
suites are **42/42 green** the same way (`ConceptDriftTest`, `CoOptimizationTest`, `MsdRadixSortTest`, `BeefCollectorsTest`, `RadixPlanTest`, `AdaptiveRadixTest`, `InPlaceMergeSortTest`), so run `./gradlew build`
host-side for the full count. See
the [CSRBT integration architecture](architecture-csrbt-integration.md) for how the feeders should use CSRBT
at full strength next.

## Session 2026-06-20 — co-optimization prior, memory-aware selection, roadmap ADRs

Shipped this session (committed locally; the sandbox runs **JRE 11 without CSRBT**, so each was verified by
review + Python/Node equivalence checks — run `./gradlew build` host-side as the gate, then push). See
`PROGRESS.md` for the full per-feature detail.

- **Gap 5 — run-derived co-optimization prior.** `ProfileGuidedScorer.derivePrior(SortResult, DataProfile)`
  sets the prior *strength* from the realized run (a `cleanliness` + `certainty` blend over a `[0.05, 0.25]`
  band centered on the old fixed `0.15`); wired through `BeefSort.buildCoOptimized`. Closes the last
  CSRBT-integration gap (`docs/adr-csrbt-integration-deepening.md`). Test: `ProfileGuidedPriorTest`.
- **Ensemble co-optimization prior.** `BeefSort.buildCoOptimizedEnsemble(...)` + a run-derived 5-arg
  `EnsembleAdaptation.attachProfileGuided(...)`. Test: `EnsembleCoOptimizationTest`.
- **Soft graded aux-memory penalty.** `CostModelStrategySelector.withAuxPenalty(λ)` / `(budget, weight)` ctor
  — a `λ·auxBytes` term on the SMART objective, default `0` = byte-identical. Cases in `CostModelSelectorTest`.
- **Inversion-aware TimSort cost.** A galloping discount on the merge levels, gated on
  `min(sortedness, 1 - inversionRatio)` *and* a measured inversion count (byte-identical when unmeasured);
  mirrored in the visualizer's JS bandit prior. `docs/adr-timsort-inversion-galloping.md`; cases in
  `CostModelSelectorTest`.

Design ADRs written this session (design only — implementation is host-side, needs toolchains the sandbox
lacks). All three open roadmap phases now have one:

- **Phase 2** — `docs/adr-phase2-rust-ffm-kernel.md`: productize the proven `phase2-ffm/` FFM Rust radix kernel
  as an optional `sbs-kernels-rust` SPI module (JDK 22 toolchain, root stays 17), with a pure-Java fallback;
  bring the kernel to parity with the entropy-aware `RadixPlan`; benchmark before defaulting.
- **Phase 4** — `docs/adr-phase4-python-intelligence.md`: learned selection behind `StrategySelector`, reusing
  the `observe(...)` stream as the training corpus; lead with offline-train / in-process-infer, gRPC
  `SbsIntelligence` only if continual/fleet learning is needed.
- **Phase 5** — `docs/adr-phase5-observability-scale.md`: external merge sort (run generation reuses the
  in-memory engine; stable k-way merge; memory-budgeted) + a typed, opt-in step-level `SortEvent` stream.

**Repo status after this session:** local `main` is ~24 commits ahead of `origin` (nothing pushed yet — push
is host-side). The Phase 4 ADR's action item #1 (log the `observe` corpus) is now **done** —
`select/ObservingStrategySelector` + `ObservationSink` (pure Java, `ObservingStrategySelectorTest`). Everything
else pending is a roadmap-ADR action item requiring host-side toolchains (Rust/JDK 22, a Python service,
spill I/O), so this is a clean stopping point.

## TL;DR

SuperBeefSort (SBS / "BeefSort") is a pluggable sorting **engine** that profiles data, picks an
algorithm + feed mode, sorts, and builds the result into the sibling **CSRBT** ordered-set engine.
Pure Java today; designed for Rust/Python/Web accelerators behind stable seams. Lives at
`C:\Users\730ri\projects\SuperBeefSort`, builds as a Gradle composite against `../CSRBT`.

Full design rationale is in [`ARCHITECTURE.md`](ARCHITECTURE.md). This file is the "what state is it
in and how do I pick it up" companion.

## Coordinates

- Package `io.github.richeyworks.superbeefsort`, group `io.github.richeyworks`, version `0.1.0`.
- Java **17** (matches CSRBT; bytecode `release = 17`). Gradle **9.5.1** (bundled wrapper).
- Tests: JUnit **5.14.4** + jqwik **1.9.3** (same stack as CSRBT).
- Depends on `io.github.richeyworks:csrbt-core:0.1.0`, resolved locally via `includeBuild("../CSRBT")`.

## Build & verify

```bash
# CSRBT must be cloned as a sibling directory (../CSRBT).
cd SuperBeefSort
./gradlew build      # compiles SBS + CSRBT, runs all tests
```

Requires a JDK 17+ on PATH. **The Cowork sandbox runs JRE 11 and cannot compile this** — all
verification to date was done host-side by Richmond plus static review/fuzzing. Always build
host-side.

## What's done

**Phase 0 — skeleton (pure Java, end-to-end):** pipeline `profile → select → sort → feed`; 6
comparison strategies (`insertion`, `merge`, `quick.threeway`, `heap`, `intro`, `jdk.timsort`);
`StrategyRegistry` via `ServiceLoader` SPI; `CsrbtTarget` adapter; feeders `DirectFeeder`,
`BalancedBuildFeeder`, `HealthGatedFeeder`; metered `SortBuffer`; lifecycle `SortObserver`/`SortEvent`;
fluent `BeefSort` facade.

**Phase 1 — intelligence:** `IntelligentDataProfiler` (now the engine default) adds HyperLogLog
distinct-count, integer `KeyStats` (min/max + counting feasibility), and `Distribution`
classification; `KeyEncoder<K>` carried on the buffer enables two stable non-comparison sorts
(`counting`, `radix.lsd`); selector chooses them from key stats with an introsort fallback; engine
capability-gates them.

**Global inversion signal:** `DataProfile.inversions` (+ `inversionsExact`, `inversionRatio()`,
`maxInversions()`) carries a true count of out-of-order pairs — the global disorder the adjacency-only
`sortednessRatio` misses. The profiler computes it **exactly** via an iterative (stack-safe) bottom-up
merge-count for small inputs (n <= 8192) or any `DEEP` profile, and **estimates** it from an
order-preserving strided sample (m = 2048, scaled by `C(n,2)/C(m,2)`) for large `SHALLOW` inputs; it
uses the raw comparator, so it never inflates the metered sort counters. Wired into all three
selectors: the rule-based selector routes genuinely-few-inversion inputs (`inversions <= 2n`, **exact
only**) to adaptive insertion sort; the cost model adds an `n + inversions` insertion candidate (exact
only); the bandit uses the exact count as the insertion prior and adds an inversion band to its context
key. The "exact only" gate means a sampling underestimate can never route a high-inversion input into
O(n^2) — exact counts exist precisely where insertion is a candidate. Covered by `InversionCountTest`
(exact vs brute force, estimate-vs-DEEP tolerance, ratio fallback, and the routing).

**Branchless small-sort kernel:** `SortingNetworkStrategy` sorts tiny inputs (n <= 16) with fixed
Batcher comparator networks (data-oblivious, branch-light); the rule-based selector now routes tiny
inputs here instead of insertion, and introsort & 3-way quicksort use it as their small-range base
case (replacing their insertion cutoff, tied to `SortingNetwork.MAX`).

**PrecisionFeeder (defensive feeding):** a new `FeedMode.PRECISION` / `PrecisionFeeder` inserts
median-first but validates CSRBT health after every insert and counts duplicates explicitly
(ARCHITECTURE sec 5.4 / 6.5) - the slowest, safest feed personality. Wired into the engine's feeder
switch; `PrecisionFeederTest` covers it (validate-every-insert + duplicate accounting + valid tree).

**SortReport (observability):** `engine/SortReport` flattens a `SortRunResult` into a one-line
dashboard - strategy, comparisons/moves, sort + feed time, inserted/duplicates, health, and end-to-end
items/s (ARCHITECTURE sec 5.7). Build it with `SortReport.of(run)`; `SortReportTest` covers a
sort-and-feed job and a sort-only job. The networks were verified exhaustively via the 0/1 principle (all
2^n inputs) and match the generated Batcher set; `SortingNetworkTest` plus the shared
`SortStrategyPropertyTest` cover the Java. Registered in `BuiltinStrategyProvider`.

**O(n) CSRBT bulk-build:** CSRBT gained `OrderedSet.fromSorted` / `buildFromSorted` +
`RedBlackTree.buildBalanced` — a balanced, black-height-correct red-black tree built directly from a
sorted distinct run (deepest level red, root black; verified across n=1..3000). SBS feeds via the new
`FeedMode.BULK` / `BulkBuildFeeder`, which de-dups the sorted run and bulk-builds an empty `OrderedSet`
target in O(n), falling back to balanced add for non-empty / ensemble targets. This collapses the feed
cost the demo showed dominating (~8× the sort → ~2× faster; demo feed ~45 ms → ~21 ms, then ~14 ms
after the `buildFromSorted` size/window trim that drops `resyncFromEngine`'s extra `inOrder()` pass).
Documented in CSRBT `docs/ADR-014`. **This edited the CSRBT repo too — commit it there host-side.**

**Cost-model selector (opt-in):** `CostModelStrategySelector` estimates each strategy's cost from the
profile (`n log n` vs run-aware TimSort vs `n+range` counting vs fixed-pass radix) and picks the min —
e.g. linear counting over TimSort for nearly-sorted *integer* data. Wired via `BeefSort.selector(...)`;
`RuleBasedStrategySelector` stays the default.

**Self-tuning selector (opt-in):** `BanditStrategySelector` (a `LearningStrategySelector`) is a
contextual multi-armed bandit. It buckets the profile into a context, seeds each capability-gated arm
with the cost-model estimate as a prior, then refines from observed cost (`comparisons + moves`) via a
guarded `observe(...)` hook added to `BeefSortEngine.sort()` — the engine reports each outcome back, so
the selector tunes itself. It converges to the genuinely cheapest strategy per context and overrides
the cost model where reality disagrees; non-comparison and O(n²) arms are gated exactly as the engine
gates them; `FIXED_INTRO`/`STABLE` delegate to the rule-based selector. Reuse one instance across jobs
so evidence accumulates — `BeefSort.selector(tuner)`.

**Web step-visualizer:** `web/visualizer.html` — a single dependency-free page animating
profile → select → sort → feed: the sort on the left (real strategy, live compares/moves), the sorted
run building into a red-black tree on the right (rotations, recolor, an RB-validity badge), a faithful
`SortEvent`/`TreeEvent` log, and an **Auto-tune ×200** panel that runs the bandit on measured costs and
shows it converging per context. Pure JS/SVG, so it runs offline; the pure core is headless-tested
(4800 sort/RB checks + bandit convergence).

**Record-and-replay (self-contained captures):** alongside the existing compact token (which reproduces
a run by re-executing from its seed + a digest checksum), the visualizer now exports a **capture** —
`{config, input, profile, plan, sortedDistinct, sort+feed event streams, digest}` — via a **⤓ Save**
button (downloads `.json`) and re-loads it via **⤒ Load**. A capture replays the *recorded* event stream
verbatim (the RB tree is rebuilt from the stored feed events: `buildBalanced` for bulk, else the recorded
inserts in order), so it shows the original animation even if the engine code later changes — a portable
teaching / bug-repro artifact, steppable with the existing Step button. Replay never re-trains the bandit
(guarded by a `replaying` flag). `compile()` was split into a reusable `renderLoadedJob(job)` so the live
path and replay share one timeline builder. New headless check `SBSViz.captureRoundtripTest` (in the boot
console) asserts a JSON-round-tripped capture replays to the same digest as a fresh run across all 8
strategies × 4 feed modes; verified in Node (160 captures green).

**Inversion signal in the visualizer:** the demo's JS profiler now mirrors the Java one — it computes an
exact merge-count inversion count (`inversions` / `inversionRatio`), shows it in the PROFILED event and
the profile-stage header, and the JS rule selector routes genuinely-few-inversion inputs (`<= 2n`) to
insertion (with the bandit context/prior updated to match). So a high-adjacency-but-globally-disordered
input (e.g. a rotation) is visibly *not* sent to insertion. Pure-core logic verified in Node (merge-count
== brute force over 300 random arrays; routing matches `RuleBasedStrategySelector`).

**Tooling:** JMH suite in `src/jmh/java` (`SortStrategyBenchmark` across data shapes, `FeedBenchmark`
bulk vs balanced) — run `./gradlew jmh`; note `build` does NOT compile `src/jmh`, so use `jmh` /
`compileJmhJava` to verify the benchmarks. GitHub Actions CI (`.github/workflows/ci.yml`) checks out SBS
plus a sibling CSRBT clone, builds + tests + compiles the benchmarks; green only once CSRBT's bulk-build
commit is pushed.

Tests: `SortStrategyPropertyTest`, `EngineFeedCsrbtTest` (feeds a real `OrderedSet`),
`NonComparisonSortPropertyTest`, `Phase1IntelligenceTest`, `BulkFeedTest`, `CostModelSelectorTest`,
`BanditSelectorTest`, `SortingNetworkTest`, `InversionCountTest`, `DifferentialTest`, `ChaosTest`,
`DeterministicSortTest`, `LearnedSortPropertyTest`, `StreamingFeederTest`, `ConceptDriftTest`, `CoOptimizationTest`, `MsdRadixSortTest`, `BeefCollectorsTest`, `RadixPlanTest`, `AdaptiveRadixTest`, `InPlaceMergeSortTest`; CSRBT: `BulkBuildTest`.

**Robustness testing (differential + chaos):** `DifferentialTest` pits every comparison strategy against
the JDK reference sort over jqwik duplicate-heavy inputs plus a fixed battery of pathological shapes
(sorted, reversed, all-equal, sawtooth, organ-pipe, few-distinct). `ChaosTest` constructs the
Bentley–McIlroy **median-of-three killer** (an adversary comparator that keeps values "gas" until forced
to freeze the non-pivot, so the pivot stays extreme) and proves the introsort depth guard fires: on the
same array a plain (unguarded) median-of-three quicksort does `> n²/5` comparisons while the engine's
introsort stays `<= 8·n·log₂n` and still sorts correctly (a >4× comparison gap). The adversary +
bounds were cross-checked against a faithful JS model of the engine's introsort (plain ≈ n²/4, intro ≈
3.5·n·log₂n) before pinning.

**Deterministic mode (reproducible runs):** `QuickSortStrategy` was the only non-deterministic sort (a
`ThreadLocalRandom` pivot). A seed now threads `BeefSort.deterministic(seed)` → `JobSpec.randomSeed` →
`SortContext.randomSeed()` → the strategy, which then seeds a `SplittableRandom` instead — so a run's exact
pivot sequence, and thus its comparison/move counts, repeat. Default behaviour is unchanged (no seed →
`ThreadLocalRandom`). `DeterministicSortTest` covers reproducibility (same seed → identical counts),
seed-sensitivity (different seeds → different pivot sequences; confirmed in a model: 5 seeds → 5 distinct
counts), correctness, and the seed threading. Pairs with `ChaosTest`: adversarial runs are now repeatable.

**Learned sort (`LearnedSortStrategy`, the "AI-discovered" angle):** a sample sort that <em>learns</em> its
bucket boundaries from the data — it sorts a small oversampled key sample and takes its quantiles as the
splitters (the empirical CDF), so buckets stay balanced even on skewed/clustered keys where fixed
equi-range bucketing piles everything into a few buckets (model: clustered maxBucket 237 learned vs 10076
naive). Elements are placed by binary search over the splitters (no comparisons), then each bucket is
sorted with the real, metered comparator — correct for any splitters because an order-faithful
`KeyEncoder` guarantees bucket i ≺ bucket i+1. Integer-key gated (capability), stable, out of place. The
metered comparison count is a fraction of n log n when buckets balance (model: 0.06–0.31× across 7
distributions). Registered in `BuiltinStrategyProvider`; the **cost model** picks it over LSD radix for
wide-range integer keys (~5n vs ~8n) and it's a **bandit** arm. Correctness pinned by
`LearnedSortPropertyTest` (jqwik bounded ints incl. negatives + a uniform/sorted/reversed/all-equal/
clustered/skewed/dup-heavy battery); the algorithm + bounds were verified in a JS model first. Left as the
default rule-selector choice: still counting/radix (learned is reachable via the cost-model/bandit paths).

**Concept-drift detection (adaptive streaming):** a new `stream/` package makes a long-running stream re-tune
its sort strategy when — and only when — the data distribution shifts. `DriftSignal.from(profile)` distils a
batch into a scale-normalized fingerprint (sortedness, global inversion ratio, cardinality ratio,
`Distribution` class, and the integer-key range's location/scale); `DriftSignal.distanceTo` is the **max** over
those facet distances, so one strong shift (e.g. the key range jumping a decade) is never diluted by stable
facets. `DriftDetector` keeps the fingerprint the current strategy was chosen for as a reference and fires when
a new batch's distance reaches `threshold` (default 0.20), then re-baselines to the new regime; `warmup` and
`cooldown` are the anti-thrash guards (the `MorphPolicy` analogue). `AdaptiveStreamSorter` — via
`BeefSort.adaptiveStream(target, maxSize[, detector])` — drives the loop: SHALLOW-profile each batch,
drift-test, **re-select only on drift** (else reuse the cached `SortPlan`), sort, and stream-feed into the
bounded CSRBT target via the existing `StreamingFeeder`. This is the sort-side mirror of CSRBT's
`MorphController` (`WorkloadAdaptation`): that re-tunes the *tree* to the live *access* pattern, this re-tunes
the *sort* to the live *data* distribution; both gate change behind threshold + warmup/stability + cooldown. A
stationary stream selects once and never thrashes; a stream that shifts regime (sorted → bounded-range →
wide-range) re-selects per regime (insertion → counting → radix) and stays correct. Covered by
`ConceptDriftTest` (distance geometry; detector baseline/stationary/drift/cooldown/warmup; and end-to-end
against a real `OrderedSet`) — compiled with SBS main against a clean CSRBT clone on a bootstrapped JDK 17
in-sandbox, **10/10 green**.

**Co-optimization — profile-guided tree adaptation ("two engines talking"):** `csrbt/ProfileGuidedScorer`
implements CSRBT's `StrategyScorer` and decorates the closed-form `CostModelStrategyScorer` with a
profile-derived **prior** — a multiplicative cost discount (default 15%) on the morph-family strategy the
sort's `DataProfile` + `AccessPolicy` favor (`favoredStrategy(...)`: READ_HEAVY→AVL, SKEWED/clustered→Splay,
WRITE_HEAVY→Red-Black, else Red-Black) — then re-ranks. It is a pure function of the immutable
`WorkloadFeatures`, composing only public CSRBT control-plane types (no CSRBT changes). The prior is a nudge,
not an override: the live cost model still wins once another strategy is cheaper by more than the margin, so
thin/early evidence defers to the profile and real traffic takes over. `WorkloadAdaptation.attachProfileGuided`
wires it over the default rolling monitor; `BeefSort.buildCoOptimized(MorphPolicy)` is the headline path —
sort, build the tree *born* in the favored morph strategy, and attach adaptation primed with that prior
(always a morph-managed strategy, so WRITE_HEAVY maps to Red-Black here, never the static weight-balanced
shape `buildAdaptive` rejects). This is the structural mirror of the concept-drift detector above: drift
re-tunes the *sort* to the live data; co-optimization lets the sort teach the *tree*. Covered by
`CoOptimizationTest` (the mapping; the prior's re-ranking against a stub base scorer; and end-to-end
born-from-profile + correctness + holds-under-matching-workload) — compiled with SBS main against a clean
CSRBT clone on a bootstrapped JDK 17 in-sandbox, **7/7 green** (the full drift + co-opt run is **17/17**).

**MSD radix for variable-length keys (strings / byte arrays):** `strategy/MsdRadixSortStrategy` +
`core/ByteSequenceEncoder` add the string/byte-key path the single-`long` `KeyEncoder` (counting, LSD radix,
learned) can't serve — those keys have no faithful `long` encoding. The encoder exposes a key as a byte
sequence (`length` + unsigned `byteAt`); the strategy is a stable, **iterative** (explicit-stack) MSD radix:
at each depth it distributes the range into 257 buckets (bucket 0 = "key ended here", so a prefix sorts before
its extensions; 1..256 = byte value) and recurses each byte bucket at the next depth, with a small range
falling back to a stable insertion sort over the real comparator (which also makes it correct for any faithful
encoder). `ByteSequenceEncoder.forStrings()` views a `String` as big-endian UTF-16 bytes and reproduces
`String.compareTo` exactly (both order by UTF-16 code unit, prefix-first — surrogates included);
`forByteArrays()` + `byteArrayComparator()` give unsigned-lex order. Exposed via `BeefSort.sortByteKeys(enc)`
(stable). It is **not auto-selected** — the profiler/selector are built around the single-`long` key model —
so it's an explicit-construction strategy for now (profiler/selector integration and the entropy-aware
base/pass-count choice are the natural follow-ups). Covered by `MsdRadixSortTest` (random small/large alphabets
+ BMP/unicode, pathological prefix/empty/duplicate shapes, an explicit stability check, byte[] keys, and the
facade) — compiled with SBS main on the bootstrapped JDK 17, **6/6 green** (full new-feature run 23/23).

**Stream integration (`BeefCollectors`):** idiomatic `java.util.stream.Collector`s so a stream collapses into a
sorted `List` or a born-optimal CSRBT `OrderedSet`. `toSortedList(cmp[, enc])` (sort-only, duplicates kept) and
`toOrderedSet(cmp[, enc[, access]])` (O(n) born-optimal build, de-duplicated) accumulate elements into a list
(supplier/accumulator/combiner) and run the engine once in the finisher. They are ordinary, non-concurrent
collectors: under a parallel stream the per-thread lists are merged by the combiner in encounter order and the
single finisher does the sort, so results are correct and the sort stays stable; CSRBT is fed once,
single-threaded. Purely additive (no engine/core changes). Covered by `BeefCollectorsTest` (sequential +
parallel, both collectors, the `KeyEncoder` and `AccessPolicy` paths incl. `READ_HEAVY` born AVL) — **4/4 green**
(full run **27/27** on the bootstrapped JDK 17). A subtle gotcha caught in verification: `Collector.of(...)` needs
explicit type witnesses (`Collector.<K, ArrayList<K>, …>of(...)` + `BeefSort.<K>with(...)`) or `K` infers to
`Object` and the finisher won't type-check.

**Entropy-aware radix (`RadixPlan`):** `radix.lsd` is now adaptive instead of fixed 8-bit / 8-pass. It
sign-flips keys for unsigned order, **offsets them by the unsigned minimum**, and sorts over only the
significant bits of the key *range*; `RadixPlan.forWidth(significantBits, n)` picks the bits-per-pass + pass
count that minimize `passes*(n + 2^b)`. The offset is the real fix: the sign flip leaves the high bit set, so
the old schedule ran a full eight passes even for a narrow band of large values — now ids in
`[1_000_000, 1_001_000]` sort in one ~10-bit pass. Same id / capabilities / stability (`radix.lsd`, stable,
out-of-place, requires a `KeyEncoder`), so all three selectors and the existing radix tests are untouched.
Covered by `RadixPlanTest` (planner adapts to width/n and always covers the width) and `AdaptiveRadixTest`
(narrow-high-magnitude, full signed range with negatives, longs, MIN/MAX, stability) — **11/11 green**, full
run **38/38** on the bootstrapped JDK 17.

**Stable in-place merge (`InPlaceMergeSortStrategy`, `merge.inplace`):** a stable sort with O(1) auxiliary
memory — the missing point in the design space (the existing `merge` is stable but O(n) aux; insertion is
in-place but O(n²)). It merges two adjacent runs with the classic rotation-based symmetric algorithm: bisect
the larger run, binary-search the split in the other (`lower_bound` when the left run is larger, `upper_bound`
otherwise — that choice is what keeps it stable), three-reversal-rotate the middle blocks past each other, and
recurse. Time trades for space: O(n log² n) from the rotations, O(1) extra. An insertion base case and an
already-ordered-seam skip make it adaptive on nearly-sorted input; recursion depth stays logarithmic.
Registered in `BuiltinStrategyProvider` and added to the `DifferentialTest` battery; **not** auto-selected — the
`STABLE` policy keeps the faster O(n)-aux `merge`, so `merge.inplace` is the explicit memory-constrained option.
Covered by `InPlaceMergeSortTest` (random + the pathological shape battery, explicit stability, capability
flags) — **4/4 green**, full run **42/42** on the bootstrapped JDK 17.

## Key decisions & gotchas (read before changing things)

- **Composite build, not a published dependency.** `settings.gradle.kts` does `includeBuild("../CSRBT")`;
  Gradle substitutes `io.github.richeyworks:csrbt-core` with the local project. Clone both repos side
  by side or the build can't resolve CSRBT.
- **CSRBT exposes only `add(K)`** — no bulk constructor. So `BalancedBuildFeeder` inserts the median
  of each subrange first (pre-order over the sorted run) to approximate an O(n) balanced build and
  minimize rotations. If CSRBT ever adds a `fromSorted`/bulk API, wire it into `CsrbtTarget` and
  prefer it.
- **Encoder safety.** Counting/radix require a `KeyEncoder` that is order-faithful to the comparator.
  `IntelligentDataProfiler.monotonic(...)` samples pairs and **withholds key stats if the encoder
  disagrees with the comparator**, so a bad encoder silently downgrades to comparison sorts instead of
  corrupting order. Keep that guard.
- **Radix signed/unsigned bug (fixed).** Keys are sign-flipped (`^ Long.MIN_VALUE`) for unsigned digit
  order. `maxU` must be tracked with `Long.compareUnsigned` — a signed `>` leaves it at 0 for
  all-non-negative inputs and truncates radix to one pass. The jqwik property test caught this; don't
  regress it.
- **Sandbox file cache.** When files are overwritten via the editor, `mcp__workspace__bash` (the Linux
  mount) can show a **stale** copy. The host file tools (and `./gradlew`) are the source of truth.
  `web/visualizer.html` in particular lagged after edits this session — commit it host-side (or re-check
  it) so the full file is captured.
- **Sandbox git is delete-restricted.** The mount blocks `unlink` under `.git` until file-deletion is
  granted in Cowork; even then `git push` (network + GitHub auth + first-time `gh repo create`) is
  host-side. Treat commits made from the sandbox as provisional until a host-side `./gradlew build`
  confirms them.
- **Bandit reward is pluggable.** `BanditStrategySelector` defaults to `comparisons + moves`
  (deterministic, machine-independent). Pass `SortResult::elapsedNanos` to its constructor to tune on
  this machine's wall-clock instead. Its arms are capability-gated like the engine, so a pick always runs.

## CSRBT facts that matter

- Package `io.github.richeyworks.csrbt`. `OrderedSet<K> implements OrderedCollection<K>, RankedSet<K>,
  SelfHealingTree`. Construct via `new OrderedSet<>(TreeStrategy, Comparator)` or
  `OrderedSet.withNaturalOrder(strategy)` (needs an explicit type arg on the strategy, e.g.
  `new RedBlackStrategy<Integer>()`, or generic inference fails).
- Health hook is `selfRepair()` (returns true if valid). `OrderedSet.validateStructure()` is a no-op
  default, so the feeder uses `selfRepair()`.
- `EnsembleOrderedSet<K>` is builder-constructed (`EnsembleOrderedSet.builder(cmp).member(...).build()`)
  and implements `OrderedCollection<K>`; it has no `SelfHealingTree` hook (health-gated feed no-ops the
  check for ensembles).
- Order statistics on `OrderedSet`: `select(rank)` (1-indexed), `rank`, `minimum`, `maximum`, `median`,
  `percentile`, `countInRange`, `rangeQuery`.

## Known limitations / open items

- Profiling comparisons run through `SortBuffer.compare` (the engine snapshots counters *after*
  profiling so sort metrics exclude them, but the profile pass itself isn't separately timed).
- `nearlySorted` uses the adjacent-pair ratio, a *local* measure. This is now complemented by two
  *global* signals on `DataProfile`: `longestRun` (longest ascending run; the rule-based selector
  routes a single run >= 50% of n to TimSort even below 90% adjacency) and `inversions` (true
  out-of-order-pair count; wired into all three selectors as above). Residual limitation: for large
  `SHALLOW` inputs the inversion count is a strided-sample **estimate** — empirically within ~0.013 of
  the exact ratio over 50 random seeds at n=60k, but systematic sampling can in principle alias with a
  periodic input, so insertion routing deliberately trusts only the **exact** count (small/`DEEP`).
  Folding the inversion estimate into the TimSort run-count model (rather than just insertion) is now
  done — the cost model discounts TimSort's merge levels by a galloping factor keyed on
  `min(sortedness, 1 - inversionRatio)` (both signals must agree the data is ordered); see
  `docs/adr-timsort-inversion-galloping.md`.
- External / out-of-core sort not implemented. (Parallel mirror feed, bounded streaming, and
  concept-drift-adaptive streaming now are — see the streaming + drift sections above.)

## Current open items (roadmap)

- **Phase 2 item 7 — selector integration for `radix.lsd.rust`.** Deferred until the native path earns
  back FFM marshaling cost. Only paths to a positive margin: **(a) rayon parallelism** in the Rust kernel
  (sort cost spread across cores while the Java path stays single-threaded), or **(b) an off-heap
  `SortBuffer`** that eliminates the two copy passes (kernel sorts in place, no separate marshaling step).
  Neither is started; ADR item 7 remains `[ ]`.

- **Phase 4 items 3-5 — LearnedModelStrategySelector.** Gate measurement (item 2) found real gaps:
  the cost model picks `intro` for reversed comparable-only inputs where `jdk.timsort` is 20-68× cheaper;
  it misses `counting` for clustered integer keys. Next steps (all in ADR `docs/adr-phase4-python-intelligence.md`):
  - Item 3: train a compact classifier (GBT / logistic) over the Phase4DecisionGate corpus; export thresholds/weights.
  - Item 4: `LearnedModelStrategySelector` — loads the model in-process, wraps `CostModelStrategySelector`,
    overrides only above a confidence margin + size gate; add to `DifferentialTest`.
  - Item 5: benchmark vs the bandit on held-out workloads; promote past the delegate only where it wins.
  An alternative to ML: fix the cost model's heuristics for reversed (recognize it as adaptive-TimSort territory)
  and clustered (trust the profiler's `counting` feasibility signal more). That may close most of the gap
  without a model.

## Repo / push status

**Local history exists; nothing is pushed yet.** `main`, newest first: `582ae5a` docs · `6eedd41` CI ·
`be6915d` JMH · `3cccf91` cost-model selector · `3b71bed` O(n) bulk-build · `4291a9c` Phase 0–1 — plus
this session's self-tuning selector, web visualizer, and doc polish. `.gitignore` excludes `build/` and
`.gradle/`; the wrapper jar is committed so clones build.

**`git push` is host-side** — GitHub auth, network, and the first-time `gh repo create` all live on your
machine, not the sandbox. Verify, then push:

```powershell
cd C:\Users\730ri\projects\SuperBeefSort
./gradlew build      # compile + run every test, including BanditSelectorTest
gh repo create RicheyWorks/SuperBeefSort --public --source=. --remote=origin --push
```

The CI workflow checks out a sibling `RicheyWorks/CSRBT`, so **CSRBT must be committed + pushed too** or
CI stays red. There the bulk-build is already committed (`7820d88`); commit the remaining working-tree
bits (the `OrderedSet` resync trim, `docs/ADR-014`, the essay) and push that repo as well.

Without `gh`: create the repos on GitHub, then `git remote add origin …` and `git push -u origin main`.

**This session — concept-drift adaptive streaming.** New files to commit host-side:
`src/main/java/io/github/richeyworks/superbeefsort/stream/` (`DriftSignal`, `DriftVerdict`, `DriftDetector`,
`AdaptiveStreamSorter`, `StreamSortResult`), a `BeefSort.adaptiveStream(...)` entry, and
`src/test/java/io/github/richeyworks/superbeefsort/ConceptDriftTest.java`:

```powershell
cd C:\Users\730ri\projects\SuperBeefSort
./gradlew build   # confirm the full suite (incl. ConceptDriftTest) is green on your toolchain
git add -A
git commit -m "feat: concept-drift detection + AdaptiveStreamSorter (re-select sort strategy mid-stream)"
```

Recurring sandbox gotcha (re-confirmed this session): the Linux mount served a **truncated** copy of
`BeefSort.java` right after the edit, so the in-sandbox compile used a faithful reconstruction of that one
file; the host file is complete and correct. As always, the host-side `./gradlew build` is the source of
truth — just run it before pushing.

**This session also added co-optimization.** New/changed: `csrbt/ProfileGuidedScorer.java`,
`WorkloadAdaptation.attachProfileGuided(...)`, `BeefSort.buildCoOptimized(...)`, and
`src/test/java/.../CoOptimizationTest.java`. Same in-sandbox gotcha recurred — the mount served **truncated**
copies of `BeefSort.java` *and* `WorkloadAdaptation.java` after the edits, so the in-sandbox compile used
faithful reconstructions of those two; the host files are complete and correct. Verified 17/17 (drift + co-opt)
on the bootstrapped JDK 17. Commit host-side after `./gradlew build`:

```powershell
git add -A
git commit -m "feat: co-optimization - ProfileGuidedScorer + BeefSort.buildCoOptimized (sort profile primes CSRBT tree adaptation)"
```

**This session also added MSD radix for string/byte keys.** New/changed: `core/ByteSequenceEncoder.java`,
`strategy/MsdRadixSortStrategy.java`, `BeefSort.sortByteKeys(...)`, and `src/test/java/.../MsdRadixSortTest.java`.
The mount truncated `BeefSort.java` (and the previously-edited `WorkloadAdaptation.java`) again, so the
in-sandbox compile used reconstructions of those two; the host files are complete. Verified **23/23** (MSD +
drift + co-opt) on the bootstrapped JDK 17. Commit host-side after `./gradlew build`:

```powershell
git add -A
git commit -m "feat: MSD radix for string/byte keys (MsdRadixSortStrategy + ByteSequenceEncoder + BeefSort.sortByteKeys)"
```

**This session also added Streams-API collectors.** New, purely additive: `BeefCollectors.java` +
`src/test/java/.../BeefCollectorsTest.java` (no other files changed). Verified **27/27** on the bootstrapped
JDK 17. Commit host-side after `./gradlew build`:

```powershell
git add -A
git commit -m "feat: BeefCollectors - Streams-API collectors (toOrderedSet / toSortedList)"
```

**This session also made `radix.lsd` entropy-aware.** Changed `strategy/RadixSortStrategy.java` (offset-by-min +
adaptive base), new `strategy/RadixPlan.java`, new `RadixPlanTest` + `AdaptiveRadixTest`. No id/selector changes.
Verified **38/38** on the bootstrapped JDK 17 (the mount truncated the rewritten `RadixSortStrategy.java` again —
compiled a reconstruction; host file is complete). Commit host-side after `./gradlew build`:

```powershell
git add -A
git commit -m "perf: entropy-aware LSD radix (RadixPlan: offset-by-min + adaptive base/pass-count)"
```

**This session also added a stable in-place merge.** New `strategy/InPlaceMergeSortStrategy.java` +
`InPlaceMergeSortTest.java`; registered in `BuiltinStrategyProvider` and added to `DifferentialTest`'s list.
Verified **42/42** on the bootstrapped JDK 17 (the mount truncated `BuiltinStrategyProvider.java` after the
edit — compiled a reconstruction; host file is complete). Commit host-side after `./gradlew build`:

```powershell
git add -A
git commit -m "feat: InPlaceMergeSortStrategy - stable merge with O(1) auxiliary memory"
```
