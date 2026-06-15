# SuperBeefSort — System Architecture

**A polyglot, dual-domain, pluggable sorting engine that intelligently feeds CSRBT.**

Version 0.1 · High-level architecture · Status: design

---

![SuperBeefSort architecture — the implemented v0.1 pipeline](architecture.svg)

> One pipeline, every stage pluggable: **profile → select → sort → feed → CSRBT**. The selector can
> learn from what it runs (the `observe()` loop), and the whole run streams to the web step-visualizer.

---

## 0. One-paragraph summary

SuperBeefSort (SBS, "BeefSort") is a sorting **engine**, not a sorting library. It inspects data like a vision-based inspection station, picks an algorithm and a feeding personality the way a smart industrial line would, executes the hot loop on whichever runtime is fastest for that data shape, and then feeds the result into the **CSRBT** (Composable Self-Balancing Tree Engine) `OrderedSet<K>` / `EnsembleOrderedSet<K>` while respecting CSRBT's health gates, strategy system, and order statistics. It is **Java at the spine** (because that is where CSRBT lives), **Rust at the muscle** (high-performance kernels), **Python at the brain** (profiling, learned strategy selection), and **TypeScript at the eyes** (step-by-step visualization and observability). Crucially, every non-Java runtime is an *optional accelerator behind a stable interface* — the pure-Java path always works on its own.

---

## 1. Requirements

### 1.1 Functional
- Sort in-memory collections, streams, and chunked sources of `K` under a single `Comparator<K>`/`Comparable<K>` contract.
- Offer many sorting algorithms (classical CLRS, non-comparison, adaptive/hybrid, parallel, learned) behind one pluggable interface.
- **Profile data before sorting** and automatically select an algorithm + feeding mode.
- **Feed CSRBT efficiently**: build/insert into `OrderedSet<K>` and shard into `EnsembleOrderedSet<K>`, honoring health validation and order statistics.
- Emit rich, step-level observability for visualization, metrics, and training/debug modes.
- Let new strategies, profilers, and feeders be added without modifying the core.

### 1.2 Non-functional
- **Performance**: the fast path should approach memory-bandwidth limits (native radix/parallel kernels); orchestration overhead must be negligible in throughput mode.
- **Correctness & safety**: never corrupt the target tree; deterministic and reproducible (seeded) runs; stable sort where promised.
- **Extensibility**: strategy-first design; languages are an implementation detail behind seams.
- **Graceful degradation**: any accelerator (Rust/Python/Web) can be absent and the engine still runs in pure Java.
- **Observability**: metrics and traces are first-class, but zero-cost when disabled.

### 1.3 Constraints & assumptions
- Java is mandated as the orchestration/integration language because CSRBT is Java.
- We **assume** CSRBT exposes, at minimum: ordered insertion, a comparator/ordering contract, a health/validation status, ensemble member access, and order-statistic queries. We *hope* it exposes a bulk/`fromSorted` construction path; if it does not, we emulate an equivalent with balanced insertion order (see §6.2). The architecture is designed to degrade cleanly against whichever surface CSRBT actually provides.
- Target JDK 22+ so we can use the Foreign Function & Memory API (Project Panama) for zero-copy native calls; JNI is the fallback.

---

## 2. Key design principles

1. **Strategy-first, everything pluggable.** `SortStrategy`, `DataProfiler`, `StrategySelector`, `SortFeeder`, and `SortObserver` are all interfaces resolved through a registry (Java `ServiceLoader` SPI). Adding an algorithm is dropping in a class — never editing the core.
2. **Control plane vs. data plane.** Java *decides* (profile, select, schedule, feed, observe). Native/learned runtimes *execute* the hot loop. The two planes meet only at narrow, well-typed seams.
3. **Profile before you sort.** A `DataProfiler` is the engine's "inspection station." Sorting decisions are driven by measured data characteristics, not guesses.
4. **Polyglot behind one contract.** Languages cross only at three defined seams (FFM buffer, gRPC protobufs, event JSON schema), all defined once in a shared `sbs-protocol` module. This is what keeps "polyglot" from becoming chaos.
5. **Pure-Java is always sufficient.** Rust, Python, and Web are accelerators. If they're missing, the selector falls back to a Java strategy with the same capabilities. Correctness never depends on an accelerator.
6. **Health-aware, defensive feeding.** Feeders respect CSRBT health gates and a shared ordering contract; the comparator used to sort *must* equal the comparator CSRBT orders by, validated at the boundary.
7. **Observable by construction, zero-cost when idle.** Step events are emitted through the pipeline, but throughput modes elide them so the fast path stays fast.
8. **Deterministic & testable.** Seeded runs, property-based tests, and a sort-benchmark-style harness validate every strategy and feeder.
9. **Backpressure & streaming-native.** Feeders handle bounded memory, chunking, and backpressure into CSRBT instead of assuming everything fits in RAM.
10. **Fail safe, fail loud.** Typed error hierarchy, explicit recovery strategies, no silent data loss; native panics are caught at the FFM boundary and fall back to Java.

---

## 3. Overall system architecture (layered view)

```
                         ┌───────────────────────────────────────────────┐
                         │  L0  API / Facade            (Java)            │
                         │  BeefSort fluent builder · BeefSortContext     │
                         └───────────────────────────────────────────────┘
                                              │
 ┌───────────────────────────────────────────────────────────────────────────────────┐
 │  L1  ORCHESTRATION / CONTROL PLANE        (Java)                                     │
 │  BeefSortEngine · Pipeline · StrategySelector · Scheduler · FeedController           │
 │  StrategyRegistry (SPI) · Config · Typed error model                                │
 └───────────────────────────────────────────────────────────────────────────────────┘
        │                         │                         │                    │
        ▼                         ▼                         ▼                    ▼
 ┌───────────────┐        ┌───────────────┐        ┌────────────────┐   ┌────────────────┐
 │ L2 INTELLIGENCE│       │ L3 EXECUTION / │        │ L4 FEEDING /   │   │ L5 OBSERVABILITY│
 │   PLANE        │       │  KERNEL PLANE  │        │  INTEGRATION   │   │   PLANE         │
 │  (Python, opt) │       │  (Rust, opt)   │        │  (Java)        │   │ (Java emit +    │
 │ DataProfiler ML│       │ sort kernels   │        │ SortFeeder ×N  │   │  TS/Web view)   │
 │ Learned select │       │ radix · pdq    │        │ CsrbtTarget    │   │ event bus       │
 │ Autotuner      │       │ parallel/SIMD  │        │ bulk-build     │   │ metrics · traces│
 └───────────────┘        └───────────────┘        └────────────────┘   └────────────────┘
        │  gRPC / GraalVM         │  Panama FFM / JNI        │  in-JVM API        │ WS / SSE
        └─────────────────────────┴─────────────────────────┴────────────────────┘
                                              │
                         ┌───────────────────────────────────────────────┐
                         │   CSRBT  —  OrderedSet<K> · EnsembleOrderedSet  │
                         │   (existing Java engine: RB-tree, health,       │
                         │    strategies, ensembles, order statistics)     │
                         └───────────────────────────────────────────────┘

  Cross-cutting (all layers):  sbs-protocol  (FFM buffer layout · gRPC protobufs · event schema)
```

The shape to notice: **one control plane (Java) surrounded by three optional satellite planes** (intelligence, execution, observability) plus a **feeding plane** that is the bridge to CSRBT. Data flows down-and-across; decisions stay in Java.

---

## 4. Technology stack per layer (and why)

| Layer | Language / Tech | Why this language wins here | Seam to Java |
|---|---|---|---|
| L0 Facade / API | **Java** | Native ergonomics for CSRBT users; fluent builder; type-safe generics. | n/a (same JVM) |
| L1 Orchestration / Control | **Java** | Lives next to CSRBT; rich ecosystem for executors, SPI, DI; the "spine." | n/a |
| L2 Intelligence | **Python** | Where ML, statistics, and learned/AlphaDev-style work actually live (NumPy, scikit, PyTorch). | **gRPC** service (default) or **GraalPython** in-process (embedded mode) |
| L3 Execution kernels | **Rust** | Memory-safe, no GC pauses, SIMD/`std::simd`, `rayon` parallelism, predictable latency — ideal for hot sort loops. | **Project Panama FFM** (zero-copy `MemorySegment`); **JNI** fallback |
| L4 Feeding / Integration | **Java** | Must touch CSRBT internals/API directly; bulk-build and health logic belong in-JVM. | n/a |
| L5 Observability / Viz | **TypeScript** + Canvas/WebGL (e.g. p5.js / d3) | Best-in-class interactive visualization; decoupled from the engine. | **WebSocket/SSE** event stream (JSON schema) |
| (future) Distributed nodes | **Rust or Go** | Throughput-oriented worker nodes for external/cluster sort. | gRPC |
| Build / test | **Gradle** (multi-module), **Cargo**, **JMH**, **Criterion** | Per-language native toolchains, one orchestrating build. | — |

**Guiding rule:** Java is the default; another language is introduced *only* where it is objectively superior, and *only* behind one of the three seams. No fourth integration mechanism is allowed without a deliberate decision.

---

## 5. Core components & responsibilities

The engine is a **pipeline of pluggable stages**. Below are the keystone interfaces (Java, sketched — names are stable, signatures illustrative).

### 5.1 `SortStrategy<K>` — the pluggable algorithm unit
Encapsulates a single sorting algorithm. The buffer abstraction is what lets the *same* interface cover an on-heap Java array and an off-heap native segment driven by Rust.

```java
public interface SortStrategy<K> {
    SortResult sort(SortBuffer<K> buffer, SortContext<K> ctx);
    StrategyCapabilities capabilities();   // metadata the selector reasons over
    StrategyId id();                        // e.g. "quick.dual-pivot", "radix.lsd.rust"
}
```

- `SortBuffer<K>` — abstracts on-heap (`Object[]`/primitive arrays) vs. off-heap (`MemorySegment`). Strategies operate through it, so a Java strategy and a Rust kernel are interchangeable.
- `SortContext<K>` — carries the comparator, the `DataProfile`, the observer hook, a cancellation token, and config.
- `StrategyCapabilities` — declares: stable?, in-place?, comparison vs. non-comparison, requires bounded-integer keys?, parallelizable?, streaming-capable?, expected best/worst input shapes, backing runtime (JAVA/RUST/PYTHON). **This metadata is what the selector reads to choose correctly.**

### 5.2 `DataProfiler<K>` — the inspection station
Analyzes data and produces a `DataProfile`. This is where "AI vision" of data lives, in software form.

```java
public interface DataProfiler<K> {
    DataProfile profile(SortBuffer<K> buffer, ProfileDepth depth);
}
```

`DataProfile` reports: size `n`; key type; **sortedness ratio** (a local, adjacent-pair measure) and longest existing run; a **global inversion count** (`inversions` / `inversionRatio()` — the out-of-order-pair distance the adjacency ratio misses, exact for small/`DEEP` inputs and a strided-sample estimate otherwise, flagged by `inversionsExact`); **distinct-value estimate** (HyperLogLog); distribution class (uniform / skewed / clustered); entropy (bits); min/max (enables non-comparison sorts); comparator-cost estimate; and a ranked list of recommended strategy families. `ProfileDepth` ranges from `SHALLOW` (O(1) sampling) → `DEEP` (full scan) → `ML` (delegate to the Python intelligence plane).

### 5.3 `StrategySelector<K>` — the decision maker
Maps a `DataProfile` + a `SelectionPolicy` to a concrete `SortPlan`.

```java
public interface StrategySelector<K> {
    SortPlan select(DataProfile profile, SelectionPolicy policy, StrategyRegistry<K> registry);
}
```

- Default implementation is **rule-based** (capability-driven): e.g. "bounded integer keys with a modest range → `radix`; genuinely few inversions (exact, `<= 2n`) → adaptive `insertion`; otherwise nearly sorted / one long run → `timsort`/run-aware; tiny `n` → sorting-network micro-kernel; adversarial → `introsort`."
- A pluggable **ML-backed selector** (Python) can replace it later without touching callers.
- `SortPlan` = chosen strategy id + `FeedMode` + parallelism degree + chunk size + an ordered **fallback chain** (guarantees correctness if the first choice can't run).

### 5.4 `SortFeeder<K>` — the personality of how we feed CSRBT
Turns a sorted run into tree population, with different "personalities" mirroring industrial machines.

```java
public interface SortFeeder<K> {
    FeedReport feed(SortedRun<K> run, CsrbtTarget<K> target, FeedContext ctx);
    FeedMode mode();
}
```

Implementations: `BulkBuildFeeder` (sorted + distinct → near-O(n) balanced build), `StreamingFeeder` (bounded memory + backpressure), `HealthGatedFeeder` (checks CSRBT health between batches — the "pill robot"), `HighThroughputFeeder` (large batches, minimal checks — the "mail sorter"), `PrecisionFeeder` (validate every insert, explicit duplicate policy). `CsrbtTarget<K>` adapts both `OrderedSet<K>` and `EnsembleOrderedSet<K>`.

### 5.5 `BeefSortEngine<K>` — the orchestrator
Owns the pipeline and the executor. Coordinates profile → select → execute → validate → feed → observe, synchronously or as an async job.

```java
public final class BeefSortEngine<K> {
    SortHandle submit(DataSource<K> src, JobSpec spec);                         // async
    SortResult sortAndFeed(DataSource<K> src, CsrbtTarget<K> target, JobSpec spec); // sync
}
```

### 5.6 `BeefSortContext<K>` / facade — the friendly front door
A fluent builder so the common case is one expression:

```java
BeefSort.with(comparator)
        .source(data)
        .policy(Policy.SMART)        // profile-and-decide
        .feedInto(orderedSet)        // or .feedInto(ensemble)
        .observe(observer)           // optional
        .run();
```

### 5.7 `SortObserver` / metrics — the eyes
A single event interface drives everything downstream (metrics sink, web visualizer, training mode).

```java
public interface SortObserver { void onEvent(SortEvent e); }
```

Event types: `JobStarted`, `Profiled`, `PlanSelected`, step-level `Comparison/Swap/Partition` (emitted only in `DEBUG`/`VISUAL` modes), `KernelDispatched`, `RunProduced`, `FeedBatch`, `CsrbtRotation`/`CsrbtMorph`, `JobCompleted`, `Error`. Metrics captured: throughput (items/s), comparisons, moves, kernel time, feed time, **CSRBT rotations/morphs triggered**, and health status — the cross-domain dashboard.

---

## 6. CSRBT integration (the whole point)

### 6.1 Adapter boundary
`CsrbtTarget<K>` wraps `OrderedSet<K>` and `EnsembleOrderedSet<K>` and exposes exactly what feeders need: ordered insert, optional bulk construction, health status, ensemble member enumeration, and order-statistic hooks. SBS talks to CSRBT only through this adapter, so CSRBT internals can evolve independently.

### 6.2 The big win — feed *sorted* data as a near-O(n) balanced build

![Feed modes into CSRBT — DIRECT vs BALANCED vs BULK rotation cost](feed-modes.svg)

Naively inserting `n` items one-by-one is `O(n log n)` and triggers many rotations. But SBS has *already sorted* the data, and a balanced BST can be built from a sorted sequence in `O(n)` with correct black-heights. Two paths:
- **If CSRBT exposes a bulk / `fromSorted` constructor:** hand it the sorted run directly. Best case.
- **If it doesn't:** emulate with **balanced insertion order** — insert the median of each subrange first (recursive midpoint ordering), which minimizes rotations and approximates a balanced build through the public insert API.

This is the single tightest coupling between "sort" and "tree," and it's why SBS is a *feeder*, not just a sorter.

### 6.3 Health-gated feeding
CSRBT performs health-gated morphing. `HealthGatedFeeder` reads health status between batches; if the tree degrades, it can slow down, change `FeedMode`, request a morph, or pause and surface a recovery event. Feeding never blindly overruns a stressed tree.

### 6.4 Ensembles — sorted data makes sharding trivial
For `EnsembleOrderedSet<K>`, because the run is sorted, range-partitioning is free: split the sorted run at member boundaries and feed each member a contiguous, balanced block — **in parallel**. Order statistics computed during the sort can be passed along to validate ranks and skip redundant work.

### 6.5 Ordering-contract safety
The comparator SBS sorts with **must equal** the comparator CSRBT orders by. The engine enforces a single shared ordering contract and validates it at the adapter boundary, because a mismatch would silently corrupt the tree. `FeedMode` selects strictness: `HIGH_THROUGHPUT` (bulk build, minimal checks) … `PRECISION` (validate every insert, explicit duplicate handling) … `SMART` (selector decides).

---

## 7. Pluggable strategy system

### 7.1 Mechanism
A `StrategyRegistry` keyed by `StrategyId`, populated via Java **`ServiceLoader` SPI** so third-party or future strategies are discovered at runtime with no core changes. Each strategy ships `StrategyCapabilities`; the `StrategySelector` reasons over capabilities rather than hard-coded names. Each strategy declares its backing runtime (Java / Rust / Python), so swapping a Java quicksort for a Rust pdqsort is a registry change, not a code change for callers.

### 7.2 Strategy families (the taxonomy)

- **Classical / comparison (CLRS core):** insertion, selection, shell, mergesort (top-down / bottom-up / natural), quicksort family (Lomuto, Hoare, 3-way, dual-pivot, median-of-medians), heapsort.
- **Linear / non-comparison:** counting, radix (LSD/MSD, configurable radix), bucket, pigeonhole. Gated by capability `requiresBoundedIntegerKeys`.
- **Adaptive / hybrid (the default workhorses):** introsort, pdqsort, Timsort / run-aware, glidesort-style.
- **High-throughput / industrial:** parallel merge, sample sort, chunked external/streaming sort, GPU-amenable radix — sustained throughput, "mail-sorter" personality.
- **Precision / defensive:** validating wrappers, stable + duplicate-aware, NaN/null-safe — "pill-robot" personality.
- **AI / learned:** LearnedSort, AlphaDev-style micro-kernels for small `n`, RL/ML-selected variants (Python/Rust backend, isolated from the correctness-critical path).
- **Tree / CSRBT-native:** `TreeSort` that builds directly into `OrderedSet`, preserving order statistics.
- **Experimental / flex:** bitonic, cycle, pancake, etc. — for specific constraints or fun.

### 7.3 Selection & safety
The selector picks within and across families from the profile, but always records a **fallback chain** in the `SortPlan`. Example: choose `radix.lsd.rust`; fall back to `radix.lsd.java` if the native lib is absent; fall back to `introsort.java` if keys turn out unbounded. Capability gating guarantees a non-comparison sort is never chosen for data it can't handle.

---

## 8. Polyglot seams (concrete)

There are exactly **three** cross-language seams, all defined once in `sbs-protocol`:

1. **Java ↔ Rust — Project Panama FFM.** Share an off-heap `MemorySegment`; Rust sorts it in place; zero copy. Bindings generated with `jextract`; built via Cargo. JNI is the fallback for pre-22 JVMs. A native panic is caught at the FFM boundary → fall back to the Java strategy in the plan.
2. **Java ↔ Python — gRPC (default) or GraalPython (embedded).** The `SbsIntelligence` gRPC service exposes `Profile`, `SelectStrategy`, and `LearnedSort` RPCs — decoupled, independently scalable, can run on a GPU box. For low-latency single-process deployments, the same contract is satisfied in-process via GraalVM polyglot.
3. **Java ↔ Web — event stream.** Java emits `SortEvent` JSON over WebSocket/SSE; the TypeScript visualizer is a separate app that consumes it. No engine code depends on the UI.

Defining the buffer layout, the protobufs, and the event schema in one shared module is the keystone that keeps four languages coherent.

### Suggested module layout (polyglot monorepo)

```
superbeefsort/
├── sbs-protocol/        # FFM buffer layout · gRPC .proto · SortEvent JSON schema  (the contract)
├── sbs-core/            # Java: interfaces, BeefSortEngine, pipeline, registry, errors
├── sbs-strategies/      # Java strategy implementations (classical, adaptive, ...)
├── sbs-csrbt/           # Java: CsrbtTarget adapters + SortFeeder implementations
├── sbs-kernels-rust/    # Rust crate (radix, pdqsort, parallel) + jextract bindings
├── sbs-intelligence-py/ # Python gRPC service: ML profiler, learned selection, autotuner
├── sbs-observer-web/    # TypeScript visualizer + training mode
└── sbs-bench/           # JMH + sort-benchmark-style validation/perf harness
```

---

## 9. Data flow (end to end)

```
 DataSource ──▶ [Ingest/Adapter] ──▶ SortBuffer<K>
                                        │
                                        ▼
                               [DataProfiler] ──▶ DataProfile
                                        │
                                        ▼
                          [StrategySelector] ──▶ SortPlan (strategy + FeedMode + fallback)
                                        │
                                        ▼
        ┌──────── in-JVM ───────┐  [Execute SortStrategy]  ┌─── off-heap via Panama ───┐
        │  Java strategy         │◀────────────┬──────────▶│  Rust kernel (zero-copy)   │
        └────────────────────────┘             │           └────────────────────────────┘
                                        ▼
                              [PrecisionGate / validate] ──▶ (ordering, dup policy, nulls)
                                        │
                                        ▼
                                  [SortFeeder] ──▶ CsrbtTarget ──▶ OrderedSet / Ensemble
                                        │                                │
                                        └────────── events ──────────────┘
                                                     ▼
                                        [Observer → metrics + Web visualizer]
```

Every stage emits events; in `HIGH_THROUGHPUT` mode the step-level events are elided so the fast path pays nothing for observability.

---

## 10. Scale & reliability

- **Load targets.** In-memory up to ~10^8 keys on a large box using off-heap buffers; beyond that, streaming/external sort with bounded memory. Native radix path is designed to approach memory-bandwidth limits.
- **Parallelism.** Fork/Join for orchestration in Java; `rayon` inside Rust kernels; sample sort for partition-friendly parallelism; chunking to cap memory.
- **Failover.** Typed error hierarchy + the `SortPlan` fallback chain; idempotent, checkpointed feeds so a partially-fed batch can resume at an offset; native crashes isolated at the FFM boundary.
- **Determinism.** Seeded runs for reproducibility; property-based tests assert the ordering invariant and permutation invariant; a sort-benchmark harness tracks throughput regressions per strategy.
- **Monitoring.** Metrics + health surfaced through the observer; alert hooks on health degradation and throughput cliffs.

---

## 11. Trade-offs (made explicit)

| Decision | Chosen | Alternative | Why / mitigation |
|---|---|---|---|
| Polyglot vs. single-language | Polyglot, optional satellites | Pure Java | Power where it matters; **pure-Java path always works**, so the complexity is opt-in. v0 ships Java-only. |
| Java↔Rust bridge | **Panama FFM** (zero-copy, modern) | JNI | Less boilerplate, no copies; JNI kept as fallback for older JVMs. |
| Java↔Python | **gRPC service** (default) | GraalPython in-process | Decoupled, scalable, GPU-friendly; embedded GraalVM offered for low-latency single-process needs. |
| CSRBT population | **Bulk/balanced build** of sorted run | Naive n× insert | O(n) vs O(n log n) and far fewer rotations; falls back to balanced insertion order if no bulk API. |
| Step-level observability | Gated behind modes | Always on | Keeps the throughput path free of overhead. |
| Learned/AI strategies | Isolated slot, never on critical path | Inline in core | High ceiling, high uncertainty — always backed by a deterministic fallback. |

**What I'd revisit as it grows:** the gRPC↔GraalVM choice once real ML latency is measured; whether `sbs-kernels-rust` should expose a stable C ABI for reuse by the future distributed nodes; and whether CSRBT's actual public surface justifies a tighter, internals-level bulk-build integration.

---

## 12. Phased roadmap (build order)

### Phase 0 — Java skeleton & the core loop  *(start here)*
Define the keystone interfaces (`SortStrategy`, `DataProfiler`, `StrategySelector`, `SortFeeder`, `BeefSortEngine`, `SortObserver`) and the `StrategyRegistry`. Implement 5–6 classical strategies (insertion, mergesort, quicksort + a variant, heapsort, LSD radix). Implement a basic `feedInto(OrderedSet<K>)`, a simple observer, and the test/bench harness. **Exit criteria:** data → sort → feed CSRBT works end to end, pure Java, with tests green.

### Phase 1 — Intelligence in Java + smart feeding
Build a real `DataProfiler` (sortedness, distinct estimate, distribution, min/max) and a capability-driven rule-based `StrategySelector`. Add `FeedMode.SMART` and the `BulkBuildFeeder` (balanced build into CSRBT). **Exit:** the engine chooses algorithm + feed mode automatically and feeds CSRBT in near-O(n).

### Phase 2 — Rust kernels via Panama
Move hot paths to Rust (pdqsort/quicksort variants, radix), share off-heap buffers zero-copy, add `rayon` parallelism. Wire the fallback chain so missing native libs degrade to Java. **Exit:** measurable throughput win on large inputs, with the Java fallback proven.

### Phase 3 — Advanced feeding & CSRBT depth
Add `HealthGatedFeeder`, `StreamingFeeder` (backpressure), `PrecisionFeeder`, and **ensemble range-sharded parallel feeding**. Enrich metrics (CSRBT rotations/morphs, health). **Exit:** SBS feeds ensembles in parallel and respects health gates under load.

### Phase 4 — Python intelligence service
Stand up the `SbsIntelligence` gRPC service: ML-based profiling, learned strategy selection, `LearnedSort`, and an autotuner that learns per-workload preferences. **Exit:** ML selector can be swapped in behind the same interface and beats the rule-based selector on at least one workload class.

### Phase 5 — Observability/visualizer + distributed + "god mode"
Ship the TypeScript step-by-step visualizer and training mode over the event stream. Add external/distributed sort nodes. Integrate AlphaDev-style micro-kernels for small-`n` and a self-improving selector. **Exit:** the full polyglot demo — Java orchestrator + Rust kernels + Python brain + Web eyes — sorting and feeding CSRBT live.

Each phase delivers something usable on its own; everything after Phase 1 is an accelerator or a flex, never a prerequisite.

---

## 13. Glossary

- **SBS / BeefSort** — SuperBeefSort, this engine.
- **CSRBT** — the existing Java Composable Self-Balancing Tree Engine being fed.
- **Control plane / data plane** — Java decides; native/learned runtimes execute.
- **Seam** — a defined cross-language boundary (FFM buffer, gRPC, event schema).
- **Feed mode** — the "personality" of how a sorted run is inserted into CSRBT (throughput, precision, health-gated, streaming, smart).
```
