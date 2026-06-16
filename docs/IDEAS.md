# SuperBeefSort — ideas backlog

High-end directions, grouped by theme. Each line names the concrete mechanism, not just the buzzword.
These are a menu, not a commitment — see "Top picks" at the bottom.

## Algorithmic frontier

- ~~**Branchless small-sort kernels**~~ — ✅ done: `SortingNetworkStrategy` (Batcher networks for n ≤ 16,
  verified via the 0/1 principle); the rule-based selector routes tiny inputs to it, and **introsort &
  3-way quicksort now use the network as their small-range base case** — every recursive sort gets the
  branchless leaf for free.
- ~~**Learned sort.**~~ — ✅ done: `LearnedSortStrategy`, a sample sort that learns its bucket boundaries
  from the empirical CDF (quantiles of an oversampled, sorted key sample), so buckets stay balanced even
  on skewed/clustered data where fixed equi-range bucketing collapses; each bucket is then comparator-sorted
  (correct for any splitters). Near-linear when the model fits — verified across 7 distributions; the
  cost-model picks it over radix for wide-range integer keys, and it's a bandit arm. (A predictive
  RMI-with-cleanup variant remains a possible refinement.)
- **ips4o / glidesort** as the parallel high-throughput workhorse above introsort.
- **MSD radix** for string/byte keys; **entropy-aware radix** that picks base + pass-count from the
  profiler's distinct-count and distribution.
- **External / out-of-core merge sort** (run generation → k-way merge to disk) for data > RAM,
  feeding CSRBT as a stream.
- **Stable in-place merge** (block merge / WikiSort) to get stability without O(n) aux memory.

## Intelligence layer (make it self-improving)

- **Online autotuner** (bandit / Bayesian) that learns this machine's thresholds — insertion cutoff,
  counting-vs-radix boundary, radix base — from observed timings.
- **Cost model**: predict per-strategy runtime from profile features, pick the argmin. Train offline
  from the JMH harness, ship the model as the selector (replaces `RuleBasedStrategySelector`).
- ~~**Concept-drift detection** on streaming workloads → re-profile and re-select mid-stream.~~ — ✅ done:
  `stream/` package — `DriftSignal` (a scale-normalized per-batch fingerprint: sortedness, inversion ratio,
  cardinality, `Distribution` class, integer-key location/scale; distance = *max* over facets), `DriftDetector`
  (windowed reference comparison with threshold + warmup + cooldown, re-baselining on drift), and
  `AdaptiveStreamSorter` (`BeefSort.adaptiveStream(target, maxSize)`): SHALLOW-profile each batch, drift-test,
  re-select **only on drift** (else reuse the cached plan), sort, stream-feed. The sort-side mirror of CSRBT's
  morph controller — that re-tunes the tree to the access pattern, this re-tunes the sort to the data
  distribution. `ConceptDriftTest` (10 cases) green vs real CSRBT on a bootstrapped JDK 17.

## CSRBT-native depth (the unique angle)

- **True O(n) bulk-build.** Contribute a `fromSorted` / `buildBalanced` constructor to CSRBT that
  builds a black-height-correct RB tree from a sorted array in O(n); SBS then feeds in O(n) instead
  of emulating with median-first inserts. Biggest real win.
- ~~**Ensemble range-sharded parallel feed.**~~ — ✅ done (corrected): reading the code showed
  `EnsembleOrderedSet` is an N-version *mirror* ensemble (ADR-003), **not** range-partitioned — every
  member holds the full set. So the win is a *parallel mirror* feed: `FeedMode.PARALLEL` / `ParallelFeeder`
  feed median-first via `add()`, and an ensemble built with `parallelFanOut()` fans each add out to all
  mirror members concurrently (its own `MemberExecutor`, no SBS threads). `EnsembleParallelFeedTest`
  proves it end-to-end. See [`PHASE3-PARALLEL-FEED.md`](PHASE3-PARALLEL-FEED.md) §0. **Also done:** the
  O(n)/member fast path — CSRBT `EnsembleOrderedSet.buildAllFromSorted` (gated to an empty MIRROR/VERIFIED
  ensemble; fans `buildFromSorted` out to every member via the executor), which `ParallelFeeder` prefers
  when the ensemble is empty, falling back to median-first `add` otherwise. Built + tested green
  (JDK 17 + CSRBT in-sandbox, 63 tests).
- ~~**Co-optimization.** SBS profiler hands hints to CSRBT's `MorphController` so the sort informs the
  tree's strategy.~~ — ✅ done: `ProfileGuidedScorer` (a CSRBT `StrategyScorer`) decorates the cost-model
  scorer with a profile-derived **prior** — a multiplicative discount toward the morph-family strategy the
  profile favors (`favoredStrategy(profile, access)`: read-heavy→AVL, skewed/clustered→Splay,
  write-heavy→Red-Black). The prior nudges, never overrides (the live cost model wins once it separates the
  costs by more than the margin). `BeefSort.buildCoOptimized(policy)` builds the tree *born* in that strategy
  AND attaches `WorkloadAdaptation` primed with the prior — the sort's profile both shapes the tree at birth
  and seeds its adaptation. `CoOptimizationTest` (7 cases) green vs real CSRBT on JDK 17. Two engines talking.
- **Order-statistic-aware feeding.** Precompute ranks during the sort, pass them to CSRBT to validate
  / skip redundant work.

## Performance engineering

- **Rust radix kernel via Panama FFM** (Phase 2): zero-copy off-heap `MemorySegment` + rayon +
  `std::simd`, Java radix retained as the capability fallback. — **PoC proven** (see
  [`phase2-ffm/`](../phase2-ffm/)): a Rust LSD radix `cdylib` sorted a `long[]` via an off-heap FFM
  downcall (JDK 21, finalized API), 300 random arrays incl. negatives, 0 mismatches. Remaining: the
  Gradle module + multi-JDK toolchain, the `backingRuntime=RUST` strategy with Java fallback, an off-heap
  `SortBuffer`, and a benchmark vs Java radix.
- **Java Vector API** (`jdk.incubator.vector`) for branchless compare/partition — SIMD with no native
  build.
- **Off-heap arena buffers** to sort 10^8+ keys with zero GC pressure; NUMA-aware sample sort.

## Observability / DX (the demo that sells it)

- **Web step-visualizer** over the `SortEvent` stream: watch the partitions, then watch CSRBT's
  rotations as the run feeds in.
- ~~**Record-and-replay**: serialize `{seed, profile, plan, events}` for deterministic repro + a
  teaching mode.~~ — ✅ done: the visualizer exports/loads self-contained `.json` captures (config +
  input + profile + plan + sort/feed event streams + digest) and replays the recorded events verbatim;
  `SBSViz.captureRoundtripTest` covers the JSON round-trip.
- **JMH suite + invariant validator** (permutation + order) wired as a CI perf-regression gate.
- **OpenTelemetry spans** per pipeline stage; a `SortReport` with comparisons, moves, kernel time,
  feed time, CSRBT rotations, throughput (items/s).

## Robustness flexes

- ~~**Differential testing** of every strategy vs `Arrays.sort`~~ — ✅ done: `DifferentialTest` (jqwik
  duplicate-heavy inputs + a pathological-shape battery). jqwik **stateful** tests of the feeder against
  a live CSRBT are still open.
- ~~**Chaos mode**: inject anti-quicksort adversarial sequences to prove the introsort fallback fires.~~
  — ✅ done: `ChaosTest` builds the Bentley–McIlroy median-of-three killer and proves introsort stays
  sub-quadratic (`<= 8·n·log₂n`) where an unguarded quicksort goes `> n²/5` — the depth guard firing.
- ~~**Deterministic mode** (seeded pivots) for reproducible runs.~~ — ✅ done: `SortContext.deterministic(seed)`
  / `BeefSort.deterministic(seed)` seeds `QuickSortStrategy`'s pivot (`SplittableRandom`), so a run's exact
  comparison/move counts repeat; `DeterministicSortTest` covers reproducibility + seed-sensitivity.

## Productization

- Publish `csrbt-core` + `superbeefsort` to Maven Central (CSRBT already has the publishing plumbing).
- Stream integration: `Collectors.toOrderedSet(...)`, a parallel `Stream` sink that feeds CSRBT.

## Top picks

If you do only three, do the ones that hit the unique integration, the AI angle, and the wow-factor:

1. ~~**CSRBT `fromSorted` bulk-build**~~ — ✅ done: true O(n) build via `OrderedSet.fromSorted` + `BulkBuildFeeder`.
2. ~~**Learned / autotuned selector**~~ — ✅ done: `BanditStrategySelector`, a contextual UCB bandit
   seeded with cost-model priors and refined by observed cost, behind `StrategySelector` / the new
   `LearningStrategySelector` feedback seam.
3. ~~**Web visualizer**~~ — ✅ done: `web/visualizer.html` — profile→select→sort→feed step-by-step into
   a live red-black tree, with an auto-tune panel that watches the bandit learn from measured cost.
