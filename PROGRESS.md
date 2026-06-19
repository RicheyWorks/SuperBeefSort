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
- **WikiSort benchmarking & curve report** — `merge.wiki` and `merge.inplace` added to the JMH `SortStrategyBenchmark` (wall-clock, three shapes at n=100k). New `MoveCurveReport` (`./gradlew moveCurve`) prints the metered move/comparison growth curve for merge / merge.inplace / merge.wiki normalised to n·log₂ n — `merge.inplace`'s moves climb ~4→9 (1k→1M) while `merge.wiki` flattens ~4.6→6.8, making the O(n log n) vs O(n log² n) difference reproducible.
- **WikiSort selector routing** — `RuleBasedStrategySelector` picks `merge.wiki` under the `STABLE` policy for very large (≥ 2²¹ ≈ 2M elements) mostly-distinct inputs (stable, O(1) aux, O(n log n) moves), keeping plain merge sort for smaller or duplicate-heavy inputs (where WikiSort would only fall back to a rotation merge). Covered by `RuleBasedSelectorTest`; the 50k-distinct profile in `BanditSelectorTest` stays on `merge` (below the size threshold).
- **Cost-model + bandit STABLE routing** — `CostModelStrategySelector`'s STABLE branch now mirrors the rule-based one (large mostly-distinct → `merge.wiki`, else merge); `BanditStrategySelector` inherits it for free by delegating deterministic policies to the rule-based base. Under `SMART` all three keep optimizing comparisons+moves, where plain merge dominates WikiSort (fewer of both), so WikiSort is correctly offered only when stability is requested. Covered by `CostModelSelectorTest` + `BanditSelectorTest`.
- **JMH wall-clock + threshold tuning** — measured all comparison sorts at n=100k (JDK 21). WikiSort is the *slowest* (~32 ms random, ~2.4× plain merge): wall-clock is comparison-dominated and WikiSort has the highest comparison constant. It is dominated by plain merge on every `SortResult` metric (comparisons, moves, time) — its only edge is O(1) memory. Accordingly raised `WIKI_MIN_SIZE` 2¹⁷ → 2²¹ (~2M, ~16 MB scratch) in both selectors, so `STABLE` auto-selects WikiSort only where merge's O(n) aux is genuinely prohibitive.
- **WikiSort comparison-constant cut (threaded distinctness)** — the all-distinct gate is no longer recomputed from scratch at every merge. `WikiSortStrategy` threads a monotone `trustDistinct` flag (seeded by a one-time internal-duplicate scan of the insertion-sorted base runs): while it holds, every run produced so far is strictly increasing, so a merge decides distinctness with a single *cross-run-equal* walk (`crossRunEqual`, one comparison per element) instead of the two-comparison `fullyDistinct` (which also re-verifies within-run order). The first duplicate seen anywhere clears the flag, after which the exact, self-contained `fullyDistinct` gate is used — so the block-vs-rotation choice (hence the output, the move count, and stability) is provably identical to the old per-merge gate on every input; only the comparison count falls. Measured via `moveCurve` on shuffled distinct permutations: `merge.wiki`'s cmp/(n·log₂n) drops ~16–19% (3.21→2.71 at n=1k; 3.96→3.21 at n=1M) while moves/(n·log₂n) stay byte-identical (4.64 / 5.81 / 6.45 / 6.79). Verified green on `WikiSortTest` + `DifferentialTest` (matches the JDK reference across every pathological + duplicate-heavy shape, stable across key densities, comparisons within the O(n log n) bound).

---

## In progress

- **Duplicate-tolerant block merge for `merge.wiki`** — design complete (`docs/adr-wikisort-duplicate-block-merge.md`): a stable, O(1)-aux `blockMergeDup` path for non-distinct regions using a back merge buffer (largest values) + a front movement-imitation tag buffer (smallest distinct values), stable block selection by `(head, A/B origin)`, origin-aware buffer-backed seam merges, and a low-cardinality fallback to rotation merge. Keeps the all-distinct fast path primary. Implementation + harness verification pending.

---

## Next steps (ideas)

- **Memory- or moves-aware objective.** WikiSort is dominated by plain merge on every `SortResult` metric (comparisons, moves, time) — its sole edge is O(1) auxiliary memory, which `SortResult` doesn't expose. Surfacing peak aux memory in `SortResult` (or a memory-weighted cost function) is what would let the cost-model/bandit prefer it under `SMART`, not just `STABLE`.
