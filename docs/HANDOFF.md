# SuperBeefSort — handoff notes

Last updated: 2026-06-13. Author: Richmond (with Claude). Status: **Phase 0 + Phase 1 complete, `./gradlew build` green.**

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

**O(n) CSRBT bulk-build:** CSRBT gained `OrderedSet.fromSorted` / `buildFromSorted` +
`RedBlackTree.buildBalanced` — a balanced, black-height-correct red-black tree built directly from a
sorted distinct run (deepest level red, root black; verified across n=1..3000). SBS feeds via the new
`FeedMode.BULK` / `BulkBuildFeeder`, which de-dups the sorted run and bulk-builds an empty `OrderedSet`
target in O(n), falling back to balanced add for non-empty / ensemble targets. This collapses the feed
cost the demo showed dominating (~8× the sort). **This edited the CSRBT repo too — commit it there
host-side.**

Tests: `SortStrategyPropertyTest`, `EngineFeedCsrbtTest` (feeds a real `OrderedSet`),
`NonComparisonSortPropertyTest`, `Phase1IntelligenceTest`, `BulkFeedTest`; CSRBT: `BulkBuildTest`.

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
- `nearlySorted` uses the adjacent-pair ratio, not total inversions. It now routes to run-aware
  TimSort (O(n log n) worst case), so a "locally sorted" input with distant inversions no longer blows
  up the way plain insertion did (the demo surfaced 16M moves before this fix). A true run- or
  inversion-count signal in the profiler is still a nice-to-have.
- No CI yet; README badges are static. A GitHub Actions workflow would need to check out CSRBT
  alongside SBS (composite build) before `./gradlew build`.
- No runnable demo `main` or JMH benchmark harness yet (was offered, deferred).
- Parallel/streaming/external sort not implemented.

## Next steps (roadmap)

- **Phase 2 — Rust radix kernel via Panama FFM.** New module `sbs-kernels-rust`; off-heap `SortBuffer`
  variant; `RadixSortStrategy` with `backingRuntime = RUST`; keep the Java radix as the capability
  fallback. Targets JDK 22+ in that module only.
- **Phase 3 — deeper CSRBT.** Range-sharded **parallel** feed into `EnsembleOrderedSet` (sorted runs
  make range partitioning trivial); streaming/backpressure feeder; precision feeder; surface CSRBT
  rotations/morphs in metrics.
- **Phase 4 — Python intelligence.** `SbsIntelligence` gRPC service: ML profiler + learned strategy
  selection behind the existing `StrategySelector` interface.
- **Phase 5 — observability + scale.** TypeScript step visualizer over the `SortEvent` stream;
  distributed/external sort.

Quick wins if picking up cold: add the demo `main` + JMH module (cheap, high signal), and a CI
workflow.

## Repo / push status

Not yet pushed. `.gitignore` excludes `build/` and `.gradle/`; the Gradle wrapper jar is committed so
clones are buildable. Push (host-side), with the `gh` CLI:

```powershell
cd C:\Users\730ri\projects\SuperBeefSort
git init -b main
git add .
git commit -m "SuperBeefSort: polyglot sorting engine feeding CSRBT (Phase 0-1)"
gh repo create RicheyWorks/SuperBeefSort --public --source=. --remote=origin --push
```

Without `gh`: create an empty `SuperBeefSort` repo on GitHub, then `git remote add origin …` and
`git push -u origin main`.
```
