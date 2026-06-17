# SuperBeefSort — handoff notes

Last updated: 2026-06-16. Author: Richmond (with Claude). Status: **Phase 0 + Phase 1 complete; Phase 3
(parallel mirror ensemble feed + O(n)/member bulk fast path) shipped; Phase 2 Rust radix kernel via Panama
FFM proven as a PoC.** Plus: a global inversion signal, a learned (sample) sort, deterministic mode,
differential + anti-quicksort chaos tests, cost-model & self-tuning (bandit) selectors, a JMH suite, CI, and a
web step-visualizer with self-contained record/replay; a bounded streaming feed; and **concept-drift-aware
adaptive streaming** that re-selects the sort strategy mid-stream; and **profile-guided co-optimization** (the
sort's data profile primes the tree's strategy adaptation); plus **MSD radix for string/byte keys**, **entropy-aware LSD radix**, a **stable in-place merge**, and **Streams-API collectors**. Everything is `./gradlew build` **green**, verified
against real CSRBT from a clean clone (JDK 17/21 + Rust bootstrapped in-sandbox); the new drift + co-optimization
suites are **42/42 green** the same way (`ConceptDriftTest`, `CoOptimizationTest`, `MsdRadixSortTest`, `BeefCollectorsTest`, `RadixPlanTest`, `AdaptiveRadixTest`, `InPlaceMergeSortTest`), so run `./gradlew build`
host-side for the full count. See
the [CSRBT integration architecture](architecture-csrbt-integration.md) for how the feeders should use CSRBT
at full strength next.

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
  Folding the inversion estimate into the TimSort run-count model (rather than just insertion) is still
  open.
- External / out-of-core sort not implemented. (Parallel mirror feed, bounded streaming, and
  concept-drift-adaptive streaming now are — see the streaming + drift sections above.)

## Next steps (roadmap)

- **Phase 2 — Rust radix kernel via Panama FFM.** New module `sbs-kernels-rust`; off-heap `SortBuffer`
  variant; `RadixSortStrategy` with `backingRuntime = RUST`; keep the Java radix as the capability
  fallback. Targets JDK 22+ in that module only.
- **Phase 3 — deeper CSRBT.** Range-sharded **parallel** feed into `EnsembleOrderedSet` (sorted runs
  make range partitioning trivial) — **build-ready design in [`PHASE3-PARALLEL-FEED.md`](PHASE3-PARALLEL-FEED.md)**
  (the SBS↔CSRBT contract, a lock-free `ParallelFeeder`, fallback, tests, and the assumptions to verify
  against CSRBT first); streaming/backpressure feeder; precision feeder; surface CSRBT rotations/morphs in
  metrics.
- **Phase 4 — Python intelligence.** `SbsIntelligence` gRPC service: ML profiler + learned strategy
  selection behind the existing `StrategySelector` interface.
- **Phase 5 — observability + scale.** TypeScript step visualizer over the `SortEvent` stream;
  distributed/external sort.

All three IDEAS "top picks" are now done (O(n) bulk-build, self-tuning selector, web visualizer).
Quick wins if picking up cold: push host-side first (SBS **and** the sibling CSRBT changes, or CI stays
red), then pick a roadmap phase — Phase 3's ensemble range-sharded parallel feed is the next natural
CSRBT-native win; Phase 2's Rust kernel is the heaviest lift. (The true inversion-count signal and the
visualizer's record-and-replay captures are both now done — see above.) Smaller remaining: fold the
inversion estimate into the TimSort run-count model, or a true inversion-count signal in the JS profiler
to mirror the Java one in the visualizer.

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
