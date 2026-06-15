# SuperBeefSort

[![CI](https://github.com/RicheyWorks/SuperBeefSort/actions/workflows/ci.yml/badge.svg)](https://github.com/RicheyWorks/SuperBeefSort/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java 17](https://img.shields.io/badge/Java-17-orange.svg)](https://adoptium.net/)
[![Build: Gradle](https://img.shields.io/badge/build-Gradle%209-02303A.svg?logo=gradle)](https://gradle.org/)
[![Feeds: CSRBT](https://img.shields.io/badge/feeds-CSRBT-534AB7.svg)](https://github.com/RicheyWorks/CSRBT)
[![Status: Phase 1](https://img.shields.io/badge/status-Phase%201-1D9E75.svg)](docs/ARCHITECTURE.md)

A polyglot, dual-domain, pluggable sorting **engine** that intelligently feeds
[CSRBT](https://github.com/RicheyWorks/CSRBT). It profiles the data, picks an algorithm and a
feeding personality, sorts, then builds into CSRBT's `OrderedSet` / `EnsembleOrderedSet` while
respecting its health gates and order statistics.

It's not a sorting library — it's an engine: **profile → select → sort → feed**, every stage
pluggable. Java is the spine, and a dependency-free web step-visualizer ships today; Rust kernels and a
Python intelligence service are optional later-phase accelerators behind the same interfaces.

![SuperBeefSort architecture — profile, select, sort, feed into CSRBT](docs/architecture.svg)

**Docs:** [architecture & design](docs/ARCHITECTURE.md) · [handoff notes](docs/HANDOFF.md) · [ideas backlog](docs/IDEAS.md) · [step visualizer](web/visualizer.html)

## Status

| Phase | Scope | State |
|-------|-------|-------|
| 0 | Pure-Java skeleton: pipeline, 6 comparison strategies, SPI registry, balanced + health-gated CSRBT feeders | ✅ done |
| 1 | Intelligence: HyperLogLog profiler, integer key stats + distribution, counting/LSD-radix sorts, capability-gated selection | ✅ done |
| 2 | Rust radix kernel via Panama FFM (Java fallback retained) | planned |
| 3 | Ensemble range-sharded feeding + streaming/backpressure | planned |
| 4–5 | Python ML selection · distributed / external sort | planned |
| ✦ | **Shipped beyond the plan:** cost-model + self-tuning (bandit) selectors · branchless sorting-network kernel · precision feeder · run-aware profiling · `SortReport` · JMH · CI · web step-visualizer | ✅ done |

## Build & test

Java 17+ and the bundled Gradle wrapper (Gradle 9.5.1). This is a **composite build** that pulls in
the sibling [`../CSRBT`](https://github.com/RicheyWorks/CSRBT) project automatically — clone both
side by side:

```bash
git clone https://github.com/RicheyWorks/CSRBT.git
git clone https://github.com/RicheyWorks/SuperBeefSort.git
cd SuperBeefSort
./gradlew build      # compiles SuperBeefSort + CSRBT, runs the test suite
./gradlew run        # demo: live pipeline trace + CSRBT order statistics
./gradlew jmh        # JMH benchmarks: strategies by data shape + bulk vs balanced feed
```

Then open **`web/visualizer.html`** in any browser for the step-by-step visualizer — profile → select →
sort → feed into a live red-black tree, with an **Auto-tune** panel that learns the cheapest
strategy per data shape, **record/replay** tokens to reproduce an exact run, and a live **CSRBT
order-statistics** explorer (select / rank / median) on the built tree. No build step; pure HTML/JS/SVG.

## Quick start

```java
import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.superbeefsort.BeefSort;
import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.select.SelectionPolicy;
import java.util.Comparator;
import java.util.List;

OrderedSet<Integer> set = OrderedSet.withNaturalOrder(new RedBlackStrategy<Integer>());

BeefSort.with(Comparator.<Integer>naturalOrder())
        .source(List.of(9, 3, 7, 1, 8, 2, 5))
        .keyEncoder(KeyEncoder.ofInt(i -> i))   // unlocks linear-time counting / radix
        .policy(SelectionPolicy.SMART)          // profile, then choose the algorithm
        .observe(System.out::println)           // optional lifecycle events
        .feedInto(set);                         // sorted, balanced build into CSRBT
```

Without a `keyEncoder`, the engine behaves exactly like Phase 0 (comparison sorts only). Swap the
selection brain with `.selector(new BanditStrategySelector())` — it learns the cheapest strategy per
data shape across runs — and call `SortReport.of(result)` for a one-line dashboard of comparisons,
moves, feed time, and end-to-end throughput.

## How it works

One pipeline, every stage pluggable:

| Stage | Component | Behavior |
|-------|-----------|----------|
| Profile | `IntelligentDataProfiler` | sortedness + longest run, distinct-count (HyperLogLog), integer key stats, distribution; validates the encoder is order-faithful before trusting it |
| Select | `RuleBasedStrategySelector` (default) · opt-in `CostModelStrategySelector` · self-tuning `BanditStrategySelector` | capability/heuristic choice with a guaranteed introsort fallback; the bandit learns the cheapest per context from observed cost |
| Sort | `SortStrategy` via `StrategyRegistry` (SPI) | sorting-network · insertion · merge · 3-way quick · heap · intro · JDK · counting · LSD radix |
| Feed | `SortFeeder` + `CsrbtTarget` | `BulkBuildFeeder` (O(n)) · `BalancedBuildFeeder` (median-first) · `HealthGatedFeeder` · `PrecisionFeeder` (validate-every-insert) · `DirectFeeder` |

### Two design notes

**Median-first feeding.** CSRBT exposes only `add(K)`. Inserting an already-sorted run naively is
`O(n log n)` and triggers many rotations, so `BalancedBuildFeeder` inserts the median of each
subrange first — an `O(n)`-ish balanced build that minimizes rotations. `HealthGatedFeeder` batches
it and calls CSRBT's `selfRepair()` between batches.

![Feed modes — why feeding a sorted run in the right order minimizes CSRBT rotations](docs/feed-modes.svg)

**Safe non-comparison sorts.** Counting/radix need integer keys, supplied via a `KeyEncoder`. The
profiler samples the data to confirm the encoding agrees with the comparator's order; if it doesn't,
integer stats are withheld and the engine stays on comparison sorts — never silently reordering keys.

### Performance (measured)

![Performance characteristics — feed-mode rotations and sorting-network comparator savings](docs/performance.svg)

Straight from the verified implementations: BULK feeding does **zero** rotations at any size (vs
DIRECT's roughly-linear growth), and the Batcher small-sort networks do about half insertion's
worst-case comparisons by n=16 (63 vs 120).

And here's what that small-sort kernel actually is — the verified Batcher network the engine runs for
small ranges (n=8 shown; the same data the `SortingNetworkTest` and 0/1-principle check validate):

![Sorting network for n=8 — the Batcher comparator network the kernel runs](docs/sorting-network-8.svg)

## Adding a strategy

Implement `SortStrategy<K>`, then either register it on a `StrategyRegistry` or contribute a
`StrategyProvider` service
(`META-INF/services/io.github.richeyworks.superbeefsort.registry.StrategyProvider`) so it's
discovered on the classpath. The core never changes.

## Module map

```
src/main/java/io/github/richeyworks/superbeefsort/
├── core/      SortStrategy, SortBuffer (metered), KeyEncoder, SortContext, SortObserver, SortEvent
├── profile/   IntelligentDataProfiler, Hll, DataProfile, KeyStats, Distribution
├── select/    StrategySelector · RuleBasedStrategySelector · CostModelStrategySelector · BanditStrategySelector (+ LearningStrategySelector) · SortPlan · SelectionPolicy
├── strategy/  SortingNetwork · Insertion · Merge · Quick (3-way) · Heap · Intro · JDK · Counting · Radix
├── registry/  StrategyRegistry, StrategyProvider (SPI), BuiltinStrategyProvider
├── feed/      CsrbtTarget, FeedMode, BulkBuildFeeder, BalancedBuildFeeder, HealthGatedFeeder, PrecisionFeeder, DirectFeeder
├── engine/    BeefSortEngine, JobSpec, SortRunResult
└── BeefSort   fluent facade
```

Alongside `src/main`: `src/jmh/java/…/bench/` (JMH benchmarks), `src/test/java/…/` (JUnit + jqwik
suite), and `web/visualizer.html` (the dependency-free step-visualizer).

## License

[MIT](LICENSE) © 2026 Richmond
