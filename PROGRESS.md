# SuperBeefSort — progress tracker

## Done

All items below are committed to `main` and tests are green.

- **Phase 0** — skeleton: pipeline (profile→select→sort→feed), 6 comparison strategies, StrategyRegistry SPI, BalancedBuildFeeder, HealthGatedFeeder, DirectFeeder, SortBuffer, SortObserver/SortEvent, BeefSort facade
- **Phase 1** — intelligence: IntelligentDataProfiler (HyperLogLog, KeyStats, Distribution), counting sort, LSD radix sort, capability-gated selection
- **Phase 3** — parallel mirror ensemble feed (ParallelFeeder + EnsembleOrderedSet.buildAllFromSorted), bounded streaming feed (StreamingFeeder), O(n) bulk-build (BulkBuildFeeder via OrderedSet.fromSorted), born-optimal CSRBT builds + co-optimization
- **Phase 2 PoC** — Rust radix kernel via Panama FFM proven in `phase2-ffm/` (not productized)
- **Global inversion signal** — exact merge-count for n ≤ 8192 / DEEP, strided-sample estimate otherwise; wired into all three selectors
- **Branchless small-sort kernel** — SortingNetworkStrategy (Batcher networks n ≤ 16, verified via 0/1 principle); used as base case in introsort + 3-way quicksort
- **PrecisionFeeder** — validates CSRBT health after every insert; FeedMode.PRECISION
- **SortReport** — one-line dashboard from SortRunResult (strategy, comparisons, moves, feed time, throughput)
- **Cost-model selector** — CostModelStrategySelector estimates per-strategy cost from profile, picks min
- **Bandit selector** — BanditStrategySelector: contextual UCB bandit, cost-model priors, refines on observed cost; LearningStrategySelector feedback seam
- **Web step-visualizer** — web/visualizer.html: profile→select→sort→feed animated, auto-tune panel, CSRBT order-statistics explorer, record/replay (compact token + self-contained .json capture/export)
- **Deterministic mode** — BeefSort.deterministic(seed) → seeded quicksort pivot via SortContext
- **Learned sort** — LearnedSortStrategy: sample sort with empirical CDF quantile splitters; cost-model and bandit arm
- **Concept-drift adaptive streaming** — stream/ package: DriftSignal, DriftDetector, AdaptiveStreamSorter; re-selects sort strategy mid-stream only on detected drift
- **Co-optimization** — ProfileGuidedScorer: sort's DataProfile primes CSRBT's MorphController via a multiplicative prior; BeefSort.buildCoOptimized(policy)
- **MSD radix for string/byte keys** — MsdRadixSortStrategy + ByteSequenceEncoder; iterative (explicit-stack), 257-bucket (end-sentinel), insertion base case; forStrings() / forByteArrays(); BeefSort.sortByteKeys(enc)
- **BeefCollectors** — java.util.stream.Collector sinks: toOrderedSet / toSortedList; correct under parallel streams
- **Entropy-aware LSD radix** — RadixPlan.forWidth: offset-by-min + adaptive bits-per-pass / pass-count; narrow high-magnitude keys sort in ~1 pass
- **Stable in-place merge** — InPlaceMergeSortStrategy (merge.inplace): rotation-based symmetric merge, O(1) aux, O(n log² n); stable; registered + in DifferentialTest
- **MSD radix auto-selection** — ByteSequenceEncoder wired through profiler (DataProfile.hasByteEncoder) and all three selectors; MSD radix now auto-selected when a ByteSequenceEncoder is present
- **CLAUDE.md** — permanent codebase orientation file in project root
- **WikiSort** — `WikiSortStrategy` (`merge.wiki`): O(n log n) stable in-place block merge, the successor to `merge.inplace` (O(n log² n)). Bottom-up + iterative (O(1) stack). Each merge takes a block-merge fast path on all-distinct regions — pull the √-sized largest-element internal buffer to the far right, align the A/B seam to the block grid, selection-sort blocks by head, repair seams with buffer-backed local merges, then insertion-sort the buffer in place (no re-merge: it already holds the largest values) — and falls back to the stable rotation merge for short or duplicate-bearing runs. Registered in `BuiltinStrategyProvider`, added to `DifferentialTest`, covered by `WikiSortTest`. Verified: matches the JDK reference across random + all pathological shapes, stable across key densities, and move count grows as O(n log n) (≈25% fewer moves than `merge.inplace` at n=1M, gap widening with n), at the cost of a larger comparison constant (~3.6 n log₂ n vs 1.3 — the usual block-merge trade).

---

## In progress

_Nothing in flight._

---

## Next steps (ideas)

- **Lower WikiSort's comparison constant.** The all-distinct gate (`fullyDistinct`) costs ~1 n log n comparisons per sort; consider determining distinctness once and threading it through, or making the block path stable under duplicates so the gate can be relaxed.
- **WikiSort fast-path coverage for near-distinct data.** Today any duplicate in a merged region routes the whole merge to the rotation fallback; a stable block path tolerant of cross-run duplicates would extend the O(n log n)-move benefit to duplicate-bearing inputs.
- **JMH coverage.** Add WikiSort to `SortStrategyBenchmark` to track the move/comparison trade against `merge.inplace` and `jdk.timsort` over a range of n.
