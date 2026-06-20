# SuperBeefSort — codebase orientation

## What it does

SuperBeefSort is a pluggable Java sorting **engine** that feeds the sibling [CSRBT](../CSRBT) ordered-set engine. Every run follows a four-stage pipeline: **profile → select → sort → feed**. The profiler measures the data (sortedness, inversion count via merge-sort counting, distinct count via HyperLogLog, integer key stats, distribution class); the selector picks the cheapest algorithm from those measurements; the chosen strategy sorts the `SortBuffer` in place; a feeder then inserts the sorted run into a CSRBT `OrderedSet` or `EnsembleOrderedSet`, using median-first insertion, O(n) bulk-build, bounded streaming, or parallel mirror fan-out depending on the feed mode. Every stage is an interface — swap any piece without touching the others.

## Package map

```
src/main/java/io/github/richeyworks/superbeefsort/
├── core/      SortStrategy, SortBuffer, KeyEncoder, ByteSequenceEncoder, SortContext, SortObserver, SortEvent, StrategyCapabilities, StrategyId
├── profile/   IntelligentDataProfiler, Hll, DataProfile, KeyStats, Distribution
├── select/    RuleBasedStrategySelector (default) · CostModelStrategySelector · BanditStrategySelector · LearningStrategySelector · SortPlan · SelectionPolicy
├── strategy/  all sort algorithms (see below)
├── registry/  StrategyRegistry, StrategyProvider (SPI), BuiltinStrategyProvider
├── feed/      CsrbtTarget, FeedMode, BulkBuildFeeder, BalancedBuildFeeder, HealthGatedFeeder, PrecisionFeeder, ParallelFeeder, StreamingFeeder, DirectFeeder
├── csrbt/     AccessPolicy · StrategyAdvisor · EnsembleTargetFactory · WorkloadAdaptation · ProfileGuidedScorer
├── stream/    AdaptiveStreamSorter · DriftDetector · DriftSignal · DriftVerdict · StreamSortResult
├── engine/    BeefSortEngine, JobSpec, SortRunResult, SortReport
├── BeefSort        fluent facade
└── BeefCollectors  java.util.stream.Collector sinks (toOrderedSet / toSortedList)
```

## Key files

**`core/SortBuffer.java`** — the mutable region every `SortStrategy` operates on. Backed by an `Object[]`. Every `compare()`, `swap()`, `compareToKey()`, and `recordMove()` call is metered automatically, so strategies never track their own counts. Optionally carries a `KeyEncoder<K>` (for counting/radix/learned) and a `ByteSequenceEncoder<K>` (for MSD radix). The `get(i)` / `set(i, v)` / `size()` primitives are all a strategy needs. This is the same abstraction a future off-heap `MemorySegment` buffer (Phase 2 Rust kernel) will implement.

**`core/SortStrategy.java`** — the one-method interface every algorithm implements: `sort(SortBuffer<K>, SortContext)`. Also declares `capabilities()` (returns a `StrategyCapabilities` — stable, inPlace, comparisonBased flags) and `id()` (returns a `StrategyId` string like `"merge.inplace"`).

**`registry/BuiltinStrategyProvider.java`** — the `StrategyProvider` SPI implementation (discovered via `META-INF/services/…StrategyProvider`) that lists every built-in strategy. When adding a new strategy, add it here. The list today: `insertion`, `network` (Batcher), `merge`, `merge.inplace`, `merge.wiki` (WikiSort), `quick.threeway`, `heap`, `intro`, `jdk.timsort`, `counting`, `radix.lsd`, `radix.msd`, `learned`.

**`strategy/InPlaceMergeSortStrategy.java`** — stable merge sort with O(1) auxiliary memory (ID: `merge.inplace`). Uses a classic rotation-based symmetric merge: bisect the larger of the two runs, binary-search the split in the other run (`lower_bound` when left is larger, `upper_bound` when right is larger — the bound choice is what guarantees stability), three-reversal rotate the two middle blocks past each other, then recurse on the two half-merges. O(n log² n) because rotations cost O(n log n) per merge level. Insertion sort base case at `CUTOFF=16`; already-ordered seam skipped adaptively. This is the precursor to WikiSort (`merge.wiki`), which achieves O(n log n) with O(1) aux via block merge with an internal buffer.

**`strategy/WikiSortStrategy.java`** — O(n log n) stable, O(1)-auxiliary block merge sort (ID: `merge.wiki`), the successor to `merge.inplace` (O(n log² n)). A faithful **no-cache port of BonzaiThePenguin's WikiSort** (the Kim & Kutzner block merge) — it handles duplicate keys **natively** (no all-distinct gate). Bottom-up and iterative (O(1) call stack): insertion-sort initial runs of 4–8, then, for each doubling level, pull two √n *unique-value* internal buffers out of the data (one tags the A blocks for stable selection, one is swap scratch), break A into √n blocks, and roll the A blocks through B — drop the minimum-headed A block (head ties broken by the tag buffer ⇒ original order ⇒ stability) and buffer-merge (`mergeInternal`) the previous A block with the B values that precede it; finally insertion-sort the jumbled scratch buffer and redistribute both buffers. Each block seam is always *one A block vs B values*, so ties resolve toward A (stable). Regions too low-cardinality to yield buffers fall back to the strictly-in-place rotation merge (`mergeInPlace`). The `Iter` inner class is the fractional bottom-up range iterator; `Rng`/`Pull` are int-only bookkeeping (no per-element aux). Achieves O(n log n) **moves** (vs `merge.inplace`'s O(n log² n)) at a higher comparison constant. The implementation was developed by porting to Python and fuzzing to stability, then transcribing back to a Python mirror to verify the Java; see PROGRESS.md and `docs/adr-wikisort-duplicate-block-merge.md`.

**`select/RuleBasedStrategySelector.java`** — the default selector. Under `SMART` it routes on profile heuristics: tiny inputs → sorting network; few inversions (exact count, ≤ 2n) → insertion; long ascending run → JDK TimSort; integer keys with small range → counting; integer keys with narrow-band or wide range → LSD radix or learned; byte/string keys with ByteSequenceEncoder → MSD radix; else → introsort. Under `STABLE` it returns plain merge sort, except for very large (≥ 2²¹ ≈ 2M elements) mostly-distinct inputs, where it prefers WikiSort (`merge.wiki`). WikiSort is ~2-3× slower than plain merge in wall-clock (higher comparison constant — see JMH), so it is chosen only where merge's O(n) scratch (tens of MB at that scale) is genuinely prohibitive; it is a stable + O(1)-aux specialist, not a speed win. Duplicate-heavy input stays on merge, since WikiSort would just fall back to a rotation merge there. `CostModelStrategySelector` mirrors this; the bandit inherits it by delegating deterministic policies to the rule-based base.

**`select/BanditStrategySelector.java`** — self-tuning selector. Contextual multi-armed bandit seeded with cost-model priors, refined by observed `comparisons + moves`. Reuse one instance across jobs so it accumulates evidence.

**`engine/BeefSortEngine.java`** — runs a `JobSpec` end-to-end: profiler → selector → strategy → feeder. Calls `selector.observe(result)` after each run so learning selectors can tune themselves.

**`BeefSort.java`** — the fluent public facade. Entry point for all users. Methods: `.source(list)`, `.keyEncoder(enc)`, `.byteSequenceEncoder(enc)`, `.policy(SelectionPolicy)`, `.selector(sel)`, `.deterministic(seed)`, `.observe(listener)`, `.feedInto(set)`, `.buildOrderedSet()`, `.buildEnsemble()`, `.buildCoOptimized(policy)`, `.streaming(set, maxSize)`, `.adaptiveStream(set, maxSize)`, `.sortByteKeys(enc)`. Also `BeefCollectors.toOrderedSet(...)` / `toSortedList(...)` for the Streams API.

**`feed/BulkBuildFeeder.java`** — O(n) feed via `OrderedSet.fromSorted` / `buildFromSorted` (a CSRBT API that builds a balanced red-black tree directly from a sorted run). Falls back to median-first `add` for non-empty or ensemble targets.

## How the pieces wire together

1. `BeefSort.with(cmp).source(data).keyEncoder(enc).policy(SMART).feedInto(set)` builds a `JobSpec`.
2. `BeefSortEngine.run(spec)` calls `IntelligentDataProfiler.profile(buffer)` → `DataProfile`.
3. The active `StrategySelector.select(profile, capabilities)` returns a `SortPlan` naming a `StrategyId`.
4. `StrategyRegistry.get(id)` looks up the strategy; it calls `strategy.sort(buffer, context)`.
5. A `SortFeeder` (chosen by `FeedMode`) reads the now-sorted buffer and inserts into the `CsrbtTarget`.
6. The engine packages counters + timing into a `SortRunResult`; `SortReport.of(result)` prints it.

## Strategy system — adding a new strategy

1. Implement `SortStrategy<K>` with a unique `StrategyId` constant (`StrategyId.of("your.id")`).
2. Return correct `StrategyCapabilities` (`stable`, `inPlace`, `comparisonBased` flags matter — selectors gate on them).
3. Add it to `BuiltinStrategyProvider.strategies()`.
4. Add it to `DifferentialTest`'s strategy list so it's automatically verified against the JDK reference across all pathological shapes.
5. Add a dedicated test class that covers random + pathological shapes, stability (if `stable=true`), and capabilities.

## Build

```bash
# Requires ../CSRBT cloned as a sibling (Gradle composite build)
./gradlew build      # compile + full test suite
./gradlew jmh        # JMH benchmarks
```

Java 17+, Gradle 9.5.1 (bundled wrapper). Tests: JUnit 5.14.4 + jqwik 1.9.3.
