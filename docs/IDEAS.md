# SuperBeefSort — ideas backlog

High-end directions, grouped by theme. Each line names the concrete mechanism, not just the buzzword.
These are a menu, not a commitment — see "Top picks" at the bottom.

## Algorithmic frontier

- ~~**Branchless small-sort kernels**~~ — ✅ done: `SortingNetworkStrategy` (Batcher networks for n ≤ 16,
  verified via the 0/1 principle); the rule-based selector routes tiny inputs to it, and **introsort &
  3-way quicksort now use the network as their small-range base case** — every recursive sort gets the
  branchless leaf for free.
- **Learned sort.** Train a CDF/RMI model (via the `KeyEncoder`) to predict each element's final
  position, then a short cleanup pass. Near-linear, and the genuine "AI-discovered" angle.
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
- **Concept-drift detection** on streaming workloads → re-profile and re-select mid-stream. Mirrors
  CSRBT's own morph controller.

## CSRBT-native depth (the unique angle)

- **True O(n) bulk-build.** Contribute a `fromSorted` / `buildBalanced` constructor to CSRBT that
  builds a black-height-correct RB tree from a sorted array in O(n); SBS then feeds in O(n) instead
  of emulating with median-first inserts. Biggest real win.
- **Ensemble range-sharded parallel feed.** A sorted run makes range partitioning trivial — each
  ensemble member builds its block concurrently.
- **Co-optimization.** SBS profiler hands hints to CSRBT's `MorphController` ("uniform keys,
  read-heavy → weight-balanced") so the sort informs the tree's strategy. Two engines talking.
- **Order-statistic-aware feeding.** Precompute ranks during the sort, pass them to CSRBT to validate
  / skip redundant work.

## Performance engineering

- **Rust radix kernel via Panama FFM** (Phase 2): zero-copy off-heap `MemorySegment` + rayon +
  `std::simd`, Java radix retained as the capability fallback.
- **Java Vector API** (`jdk.incubator.vector`) for branchless compare/partition — SIMD with no native
  build.
- **Off-heap arena buffers** to sort 10^8+ keys with zero GC pressure; NUMA-aware sample sort.

## Observability / DX (the demo that sells it)

- **Web step-visualizer** over the `SortEvent` stream: watch the partitions, then watch CSRBT's
  rotations as the run feeds in.
- **Record-and-replay**: serialize `{seed, profile, plan, events}` for deterministic repro + a
  teaching mode.
- **JMH suite + invariant validator** (permutation + order) wired as a CI perf-regression gate.
- **OpenTelemetry spans** per pipeline stage; a `SortReport` with comparisons, moves, kernel time,
  feed time, CSRBT rotations, throughput (items/s).

## Robustness flexes

- **Differential testing** of every strategy vs `Arrays.sort`; jqwik **stateful** tests of the feeder
  against a live CSRBT.
- **Chaos mode**: inject anti-quicksort adversarial sequences to prove the introsort fallback fires.
- **Deterministic mode** (seeded pivots) for reproducible runs.

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
