# ADR: Phase 2 — off-heap MemorySegment buffer for the Rust radix path

**Status:** CLOSED NEGATIVE (2026-06-21). Off-heap removed the FFM marshaling penalty (parity at n=100k,
where the old pair-path was ~2.1× slower) but single-threaded native radix loses at scale (0.64× at 1M);
branch B (rayon), once its radix was capped, only reached parity at 1M (0.96×) and +3% at 5M (1.03×),
losing below. Neither meets the gate's "clear win at n ≳ 10⁵". The one lever that helped — multicore —
is better captured by a **parallel Java `radix.lsd`** (same gain, no FFM/Rust/JDK-22 dependency). **Native
radix is not integrated**; the off-heap buffer + sequential/parallel kernels are retained as a recorded,
reproducible exploration and a substrate should a future native need arise.
**Deciders:** Richmond (project owner)
**Related:** `docs/adr-phase2-rust-ffm-kernel.md` (the Rust kernel; item 7 deferred for lack of margin),
`sbs-kernels-rust/` (the FFM module), `core/SortBuffer.java` (the on-heap buffer this complements)

---

## Context

The Phase 2 JMH results closed `adr-phase2-rust-ffm-kernel.md` with a hard finding: `radix.lsd.rust`
is **slower than the pure-Java `radix.lsd` at every size** (+10% at 1k, +114% at 100k). The cause is
not the kernel — it is **FFM marshaling**. Per sort, `RustRadixSortStrategy` does:

1. **Pack** — for each element: `b.get(i)` (on-heap), `encodeKey`, then two `seg.setAtIndex(JAVA_LONG, …)`
   writes into a throwaway confined `Arena` → **2n per-element off-heap VarHandle writes**.
2. **Kernel** — `sbs_radix_sort_keyed(seg, n)` sorts the (key, index) pairs in place (fast, native).
3. **Extract** — for each element: `seg.getAtIndex(JAVA_LONG, 2i+1)` → **n per-element off-heap reads**.
4. **Permute + writeback** — build `sortedItems[]` from the on-heap `items[]`, then `b.set` ×n.

Steps 1 and 3 are pure overhead that the Java path never pays — and per-element `setAtIndex`/`getAtIndex`
go through a `VarHandle` with bounds + alignment checks, materially slower than a Java array access. The
ADR's own conclusion: no positive margin exists "without (a) rayon parallelism or (b) an off-heap
`SortBuffer` that eliminates the copy passes." This ADR prototypes (b).

`core/SortBuffer.java` already anticipates it: *"This is the same abstraction a future off-heap
`MemorySegment` buffer (Phase 2 Rust kernel) will implement."*

---

## Decision

Prototype an **off-heap, `MemorySegment`-backed buffer of primitive long keys** (`OffHeapLongBuffer`) and a
**flat-long kernel entry** (`sbs_radix_sort_longs`) that sorts that segment **in place**, so the
marshaling collapses to:

- **load** — one bulk `MemorySegment.copy(long[] → segment)` (memcpy-class, not 2n VarHandle writes);
- **sort** — kernel in place on the segment (no index payload — the values *are* the keys);
- **read** — one bulk `MemorySegment.copy(segment → long[])`.

The per-element pack (2n) and extract (n) vanish; there is no index field (so half the bytes and no
permute). This is the realistic ceiling for the FFM path and the honest test of whether native radix can
beat Java once marshaling is removed.

### Scope and the payload limit (stated honestly)

Off-heap memory holds primitives, not arbitrary `K` objects. So the zero-copy win is **only** fully real
when the elements *are* the keys (`long[]`/`int[]` workloads). For general `SortBuffer<K>` with a
`KeyEncoder<K>`, the payloads stay on-heap and a permutation must still be applied — which reintroduces an
O(n) pass. Therefore this prototype targets the **primitive-long fast path** (the radix sweet spot), not a
drop-in replacement for `SortBuffer<K>`. Bringing it into the engine would be a typed `long[]` entry point
on the facade (e.g. `BeefSort.sortLongs(...)`), not a change to the generic buffer.

---

## Options considered

- **A — off-heap long buffer + flat-long kernel (this prototype).** Eliminates pack/extract for the
  primitive-long case via bulk transfer + in-place sort. *Pro:* the only way to give native radix a real
  shot; reuses the existing kernel machinery. *Con:* primitive-long only; engine integration is a separate,
  typed fast path.
- **B — rayon parallelism in the kernel** (the other deferred path). Orthogonal — it speeds the *kernel*,
  not the *marshaling*. Best pursued only after (A) shows marshaling is no longer dominant; otherwise the
  copies still cap the win. Tracked in `adr-phase2-rust-ffm-kernel.md`.
- **C — leave it deferred.** Cheapest, but never answers whether the kernel can win.

A is the prerequisite experiment: if removing marshaling still doesn't beat Java radix, rayon (B) is the
only remaining lever; if it does, the selector-integration question (Phase 2 item 7) reopens with data.

---

## Prototype (additive, in `sbs-kernels-rust`, JDK 22)

- `rust/src/lib.rs`: `sort_flat(&mut [u64], n)` (stride-1 sibling of `sort_keyed_flat`, same `radix_plan`)
  + `#[no_mangle] sbs_radix_sort_longs(ptr, count)` + unit tests. Nothing existing is changed.
- `select/…/rust/RustRadixBridge`: a second downcall handle for `sbs_radix_sort_longs`, guarded by the same
  static-initialiser fallback (`isLongsAvailable()`).
- `OffHeapLongBuffer` — `MemorySegment`-backed long buffer: `of(long[])` (bulk copy in), `get/set/size`,
  `segment()`, `copyTo(long[])`, `AutoCloseable` (confined `Arena`). The off-heap `SortBuffer` primitive set.
- `OffHeapLongRadix.sort(long[])` — sign-flip → bulk-load → in-place kernel sort → bulk-read → un-flip.
- `OffHeapRadixBenchmark` — a self-contained timing `main` (no JMH cross-module plumbing) comparing an inline
  Java LSD radix vs the off-heap path across sizes. JMH-ification is the rigorous follow-up.

Nothing touches `core/SortBuffer` or the existing `sbs_radix_sort_keyed` path; the pure-Java fallback and
the JDK-17 root build are unaffected (the module only activates on JDK 22+).

---

## Decision gate (when this earns selector integration)

Run `OffHeapRadixBenchmark` (and later a JMH version) on the host (JDK 22 + `cargo build --release`). Promote
`radix.lsd.rust` (or a new `radix.lsd.rust.offheap`) into the selector **only if** the off-heap long path
beats pure-Java `radix.lsd` by a **margin that covers a real workload's payload-permutation cost** at the
sizes the selector would route to it — i.e. a clear win at n ≳ 10⁵, not a wash. If it merely ties Java radix,
keep it deferred and pursue rayon (B). If it loses even without marshaling, the kernel approach is dead for
this workload and the ADR closes negative.

**Done-well metric:** a reproducible host benchmark that isolates marshaling-free native radix vs Java radix
on `long[]`, with a yes/no integration verdict and the margin that backs it.

---

## Results & verdict (measured 2026-06-21, host JDK 22.0.2, `:sbs-kernels-rust:offHeapBench`)

`OffHeapLongRadix` (sign-flip → bulk copy → in-place `sbs_radix_sort_longs` → bulk copy → un-flip) vs an
inline fixed-8-pass Java LSD radix, random full-range `long[]`, avg ms/op (correctness verified against
`Arrays.sort` first):

| n | java radix (ms) | off-heap Rust (ms) | speedup |
|---|---|---|---|
| 1,000 | 0.052 | 0.020 | 2.55× |
| 10,000 | 0.278 | 0.184 | 1.51× |
| 100,000 | 1.246 | 1.244 | 1.00× |
| 1,000,000 | 11.775 | 18.415 | **0.64×** |

**Reading.** Off-heap did its job — it **removed the marshaling penalty**: the closed
`adr-phase2-rust-ffm-kernel.md` measured the (key, index) pair-path at **+114% (≈2.1× slower)** at n=100k;
the off-heap long path is **parity (1.00×)** there. So the per-element `setAtIndex`/`getAtIndex` + throwaway
`Arena` round-trip *was* the moderate-n bottleneck, as hypothesised. But at **n=1M the off-heap path is
0.64× — 36% slower** than a tight JIT'd Java radix: with marshaling gone, the ceiling is the **kernel
itself** (a single-threaded native radix has no structural edge over a JIT'd one, and pays extra traffic —
sign-flip ×2, two bulk copies, the kernel's internal offset + double-buffer). The small-n "wins" are mostly
the kernel's entropy-aware `radix_plan` beating the naive fixed-8-pass baseline, not the off-heap buffer; on
full-range random longs both schedules collapse to ~8 passes, so the 1M figure (where they agree) is the
honest signal.

**Verdict — gate NOT met** (required a clear win at n ≳ 10⁵; got parity at 10⁵, a loss at 10⁶). **Do not
integrate** `radix.lsd.rust(.offheap)` into the selector. The prototype is retained as a recorded negative
result + a validated substrate.

**Next lever — rayon (branch B), now justified.** Single-threaded native ties/loses, so a win must come from
a multi-threaded scatter the JVM can't cheaply match; the off-heap segment is the right input (no marshaling
to eat the parallel speedup). Pursue it against the **production entropy-aware `radix.lsd`** (not the
fixed-8-pass baseline), and confirm with **JMH** (forks/steady-state) before any integration claim. The
off-heap path remains `long[]`-only — arbitrary `K` payloads still need an on-heap permutation pass.

**Update (2026-06-21) — branch B prototyped.** `rust/src/lib.rs` now has `sort_flat_parallel`
(chunked parallel histogram → sequential disjoint-offset prefix → parallel scatter, stable) +
`sbs_radix_sort_longs_par`; `OffHeapLongRadix.sortParallel` and the `par` column in
`OffHeapRadixBenchmark` exercise it. Rust correctness is covered by `cargo test` (sizes spanning the
65 536-element sequential-fallback threshold). The open question is purely the n≥1M timing — does
parallel scatter flip the 0.64× single-thread loss to a win? Run `:sbs-kernels-rust:offHeapBench` (the
`parX` column) on the host; if it wins, JMH-confirm vs production `radix.lsd`, then revisit selector
integration. The parallel scatter uses one `unsafe` block (each thread writes a disjoint, in-bounds
index range — no aliasing); that invariant is what the `cargo test` cases guard.

**Branch B measured (2026-06-21, host JDK 22, 24 threads, random full-range `long[]`, avg ms/op):**

| n | java (ms) | seq (ms) | par (ms) | parX |
|---|---|---|---|---|
| 100,000 | 1.049 | 1.423 | 2.495 | 0.42× |
| 1,000,000 | 9.996 | 19.165 | 10.422 | 0.96× |
| 5,000,000 | 62.117 | 124.680 | 60.589 | 1.03× |

A first, un-capped run was catastrophic (parX 0.12–0.15× at 100k–1M) because the entropy plan's large
radix blew up the per-pass `radix × num_chunks` histogram/offset work; capping the parallel radix at 8
bits fixed that (above). (Below the 65 536 parallel threshold the kernel falls back to sequential, so the
1k/10k rows just mirror `seq`.)

**Final verdict — Phase 2 native radix closes NEGATIVE.** Parallel only reaches parity at 1M (0.96×) and
+3% at 5M (1.03×), losing below — not a clear win, and measured against a fixed-8-pass baseline that the
production entropy-aware `radix.lsd` already matches or beats. The decisive observation: the sole source
of any gain was **multicore**, which is capturable by a **parallel Java `radix.lsd`** — same speedup,
none of the FFM marshaling, Rust toolchain, `unsafe`, JDK-22 requirement, or `long[]`-only payload limit.
So `radix.lsd.rust` / `.offheap` / `.par` are **not** integrated into the selector. If large-n radix
throughput ever matters, the recommended path is parallelizing the Java radix, not the native kernel. The
artifacts here stand as a reproducible record of *why* the native route was rejected (and a substrate if a
genuinely native-only need — e.g. SIMD the JVM can't emit — arises later).

**Follow-up (2026-06-22) — recommendation implemented.** This ADR's closing recommendation ("parallelize the
Java `radix.lsd`, not the native kernel") is now realized as `strategy/ParallelRadixSortStrategy`
(`radix.lsd.parallel`): a stable, multi-threaded LSD radix using the standard chunked-histogram →
disjoint-offset prefix → parallel scatter schedule, with the per-pass radix **capped at 8 bits in the
parallel path** — exactly the cap this ADR found necessary to stop the `radix × chunks` matrix from eating
the speedup. Its output is byte-for-byte identical to sequential `radix.lsd` for any chunk count
(deterministic + stable), and the algorithm is fuzz-validated 972/972 (`PRadixCheck`, real `ForkJoinPool`:
sorted order + permutation + stability over every shape/size/chunk-count).

**Selector routing (2026-06-23, provisional).** `RuleBasedStrategySelector` (the engine default) now routes
wide-range integer inputs under `SMART` to `radix.lsd.parallel` once `n >=` a crossover pinned to the
strategy's own parallel-engage point (`ParallelRadixSortStrategy.PARALLEL_THRESHOLD = 1<<16`). The branch sits
*after* the counting gate, so bounded-range integers still take `counting`; and below the crossover the
parallel strategy would only run single-threaded (identical to `radix.lsd`), so routing there would be a no-op.
Because the parallel sort is byte-for-byte identical to sequential `radix.lsd` for any chunk count, this changes
only wall-clock, never results, so it is safe to ship ahead of the benchmark — but the constant is
**provisional**: the `bench/ParallelRadixBenchmark` JMH sweep (added the same day; params n ∈ {50k, 100k, 500k,
1M}, straddling the threshold) must be run on the host to locate the *true* profit crossover, and the constant
retuned upward if it is higher than `1<<16`. Per this ADR's "confirm with JMH before any integration claim",
the routing is in but flagged provisional, pending that measurement.

The cost-model and bandit selectors are intentionally left **unchanged**: the cost model's comparisons+moves
objective scores `radix.lsd` and `radix.lsd.parallel` identically (same passes/moves, same `LINEAR` aux class),
and `learned`'s ~5n estimate already dominates radix's ~8n there, so the cost model never picks `radix.lsd` to
begin with — a parallel swap would be dead code. The bandit likewise cannot separate the two arms on metered
cost. The parallel advantage is purely wall-clock, which only the rule-based heuristic's size gate expresses.
A wall-clock-aware cost (e.g. dividing radix passes by an effective parallelism) is the seam if cost-model/bandit
routing is ever wanted, but it is out of scope until the JMH crossover is known. See PROGRESS.md.

**JMH results (2026-06-23, host JDK 22.0.2, 1 fork, 3 warmup + 5 measurement × 2 s, random full-range `int`).**
`bench/ParallelRadixBenchmark`, avg ms/op (99.9% CI half-width):

| n | `radix.lsd` seq (ms) | `radix.lsd.parallel` (ms) | seq/par | seq rel.err | par rel.err | 99.9% CIs |
|---|---|---|---|---|---|---|
| 50,000 | 0.675 ± 0.010 | 0.598 ± 0.013 | 1.13× | 1% | 2% | separated |
| 100,000 | 1.675 ± 1.105 | 1.412 ± 0.252 | 1.19× | 66% | 18% | **overlap** |
| 500,000 | 8.168 ± 0.089 | 7.164 ± 0.523 | 1.14× | 1% | 7% | separated |
| 1,000,000 | 27.948 ± 18.242 | 19.939 ± 15.169 | 1.40× | 65% | 76% | **overlap** |

**Verdict — no statistically-significant parallel speedup was isolated; the crossover is *not* established by this
run.** The reasoning:

1. **The 50k row is a control, and it already shows a ~13% gap.** At n = 50,000 < `PARALLEL_THRESHOLD`,
   `chunkCount` returns 1, so `radix.lsd.parallel` runs as a *single-chunk* sort — **no threads**. A 13%
   "win" where parallelism is inactive is a **baseline offset** (a real implementation difference between the
   two radix code paths, and/or run-order/system drift), not a parallel benefit.
2. **After subtracting that baseline, parallelism adds almost nothing through 500k.** At 500k (the cleanest row,
   both CIs tight) the 1.14× gap is essentially the same 1.13× baseline — parallelism itself contributes ≈1%.
3. **The two rows that would show a large win (100k, 1M) are not significant** — their 99.9% CIs overlap, with
   sequential relative error of 66% and 65% (the 1M variance is consistent with GC churn from rebuilding the
   `SortBuffer`/boxed `Integer` list each op). The 1M point estimate (1.40×) only *hints* at a real
   large-n benefit — and is consistent with this ADR's own native branch-B data (parity at 1M, +3% at 5M).
4. **Structural confound:** the harness measured **all** `parallel` configs first, then **all** `sequential`
   configs (`@Fork(1)`), so any drift over the 2-minute run aliases directly onto the seq-vs-par comparison —
   exactly the bias the 50k control exposes. As JMH's own footer warns, *"do not assume the numbers tell you
   what you want."*

**Action:** `PARALLEL_RADIX_CROSSOVER` is **kept at `1<<16`** (unchanged) — the routing remains result-identical
and harmless, but this run does not justify lowering it, and the only directional hint (1M) is not significant
enough to raise it on. A **hardened re-run** is required before any retune, and the benchmark has now been
reworked for it (2026-06-23): the algorithm is a fastest-varying `@Param` so `seq`/`par` are measured
back-to-back at each n (killing the order confound), the Gradle `jmh` fork count is raised 1→3, n is extended to
2M/5M, and the 50k control row is kept to quantify the residual baseline. Re-run
`gradlew jmh -Pbench=ParallelRadixBenchmark` and set the crossover to the smallest n where `radix.lsd.parallel`
beats `radix.lsd` by **more than** that baseline offset (i.e. normalise each n's seq/par ratio by the 50k ratio).
