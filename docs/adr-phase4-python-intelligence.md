# ADR: Phase 4 — learned strategy selection (SbsIntelligence)

**Status:** In Progress (action items 1-4 closed — corpus logged, gaps measured, model trained + exported,
in-process `LearnedModelStrategySelector` built; item 5 in-JVM JMH pending)
**Date:** 2026-06-20 (items 3-4 added 2026-06-21)
**Deciders:** Richmond (project owner)
**Related:** `docs/HANDOFF.md` (Phase 4 roadmap), `select/StrategySelector` + `select/LearningStrategySelector` (the seams), `select/BanditStrategySelector` (the in-process learned baseline), `select/CostModelStrategySelector` (the analytic baseline / prior source), `profile/DataProfile` (the feature vector), `core/SortResult` (the label)

---

## Context

The roadmap's Phase 4 is "an `SbsIntelligence` gRPC service: ML profiler + learned strategy selection behind
the existing `StrategySelector` interface." The seam is already the right shape for it:

```java
interface StrategySelector { SortPlan select(DataProfile, SelectionPolicy, StrategyRegistry); }
interface LearningStrategySelector extends StrategySelector {
    void observe(DataProfile profile, StrategyId strategy, SortResult outcome);
}
```

So a learned selector is *just another `StrategySelector`*, and `BeefSortEngine` already calls `observe(...)`
after every run when the selector implements `LearningStrategySelector` — which means **the engine already
emits a labeled training stream**: `DataProfile` features → chosen `StrategyId` → measured `SortResult`
(comparisons, moves, elapsed, peak aux). `BanditStrategySelector` already consumes that stream in-process
(contextual UCB seeded with cost-model priors). Phase 4 asks whether a *richer learned model* — trained in
Python — can choose better than the bandit/cost-model, and how to serve it.

Two forces dominate the design:

1. **Selection is on the hot path, per job.** The selector runs once per sort *job* (not per element), but
   its whole job is to pick a sort that takes microseconds-to-milliseconds. A model that takes longer to
   *consult* than the sort it's choosing is a net loss for small/medium inputs. Whatever we build must be
   ~free for small jobs and only pay for inference where the payoff (a better pick on a large job) covers it.
2. **The engine must never depend on an external service to function.** A learned selector has to degrade to
   the existing `CostModelStrategySelector` / `BanditStrategySelector` when the model/service is absent,
   slow, or wrong — the same capability-fallback discipline used everywhere else in the codebase.

The existing baselines are strong (cost model + bandit), so Phase 4's bar is *measurable improvement over the
bandit on held-out workloads* — otherwise it's complexity without payoff.

---

## Decision

Adopt a **two-step, fallback-first** plan and **lead with offline training + in-process inference**, treating
the gRPC service as an optional later step rather than the entry point:

- **Phase 4a (recommended first):** log the `observe(...)` stream to a dataset; **train a model offline in
  Python** (start with gradient-boosted trees / multinomial logistic over the `DataProfile` feature vector,
  label = cheapest strategy by measured `comparisons + moves`); **export a compact model** (e.g. the tree
  ensemble as plain thresholds, or class weights) that a new `LearnedModelStrategySelector` evaluates
  **in-process in Java** — no runtime service, no network on the hot path. It wraps a delegate
  (`CostModelStrategySelector`) and only overrides the pick when the model is confident and the input is
  large enough to matter; it implements `LearningStrategySelector` so it keeps feeding the dataset.
- **Phase 4b (only if needed):** stand up the Python **`SbsIntelligence` gRPC service** and a
  `RemoteStrategySelector` client for the cases 4a can't serve — *online/continual* learning across a fleet,
  or models too large to export — behind a **size gate + circuit breaker** that falls back to the local
  selector on timeout/error.

Either way the learned selector is one more `StrategySelector`; the engine, registry, and facade are
untouched.

---

## Options Considered

### Option A — Offline training, in-process inference *(recommended, Phase 4a)*

Python trains from the logged `observe` corpus; the model is exported and evaluated inside the JVM.

| Dimension | Assessment |
|---|---|
| Hot-path latency | **Best** — microsecond in-process inference; no network |
| Availability | **Best** — no runtime dependency; pure-Java fallback is trivial |
| Ops/Complexity | Low-Med — a training script + a model-export format + a small Java evaluator |
| Online adaptation | **None** — model is refreshed by re-training/redeploy, not continuously |

**Pros:** removes both dominant risks (latency, availability) entirely; reuses the existing training stream;
in-process inference is trivially testable against the JDK-reference like any selector. **Cons:** no
continual learning; a model-export contract to maintain; cross-process learning needs a redeploy cadence.

### Option B — Runtime gRPC service `SbsIntelligence` + `RemoteStrategySelector` (the roadmap's literal ask)

The Java selector calls a Python service per (gated) selection; the service also ingests the `observe` stream
for online learning.

| Dimension | Assessment |
|---|---|
| Hot-path latency | **Risk** — a network RPC per job; only viable behind a size gate |
| Availability | Needs a circuit breaker + local fallback to stay correct when the service is down |
| Ops/Complexity | High — a deployed service, proto contract, versioning, monitoring |
| Online adaptation | **Best** — continual, fleet-wide learning; hot-swappable models |

**Pros:** continual/fleet-wide learning; model size unconstrained; iterate on the model without a JVM
redeploy. **Cons:** introduces a runtime distributed dependency for an in-process decision; latency forces a
size gate anyway; most of its value (a better-trained model) is *also* obtainable via Option A without the
service.

### Option C — Embedded Python in the JVM (JEP / GraalPy)

Run the Python model in-process via an embedded interpreter.

**Pros:** no network; reuse Python model code directly. **Cons:** heavyweight runtime coupling, packaging and
GIL/threading pain, a second language runtime in every deployment — for no benefit Option A's exported model
doesn't already give. **Rejected.**

---

## Trade-off Analysis

The roadmap names a gRPC service, but the **hot-path latency** and **availability** forces argue for *earning
the model first and the service only if needed*. Option A captures the headline value — "a learned model that
beats the heuristics" — with none of the distributed-systems surface, and it reuses infrastructure that
already exists (the `observe` stream, the capability fallback, the differential test). The gRPC service (B) is
the right tool only for what A can't do: *continual* learning across many processes and very large models.
Sequencing A→B means we never pay for B until A's offline model has demonstrated a real, measured win over the
bandit — avoiding "build the service, then discover the model doesn't beat the cost model."

A subtle point favors A regardless: selection quality is bounded by **feature parity**. The model can only use
what `DataProfile` exposes (size, sortedness, inversions/ratio, distinct estimate, key stats, distribution,
longest run, byte-key flag). Training and serving on the *same* Java-computed features is trivial in-process
(A) and a serialization contract to keep in lockstep across a network (B). Start where parity is free.

---

## Consequences

**Easier:**
- A learned selector slots in behind `StrategySelector` with the engine/registry/facade untouched, and is
  validated against the JDK reference by the existing differential harness like any other selector.
- The `observe(...)` stream becomes a first-class, logged **dataset**; even before any model ships, that
  dataset quantifies how often the cost model/bandit are sub-optimal — which itself tells us whether Phase 4
  is worth finishing.

**Harder / to revisit:**
- A **dataset-logging** path and a **model-export contract** (Option A) — kept small: a stable feature order
  + a thresholds/weights blob the Java evaluator reads.
- If Phase 4b lands, a deployed service brings proto versioning, a **size gate** (consult the model only above
  an `n` where a better pick amortizes the RPC), a **circuit breaker** (timeout/error → local fallback), and
  monitoring — real distributed-systems weight for an in-process decision.
- Guarding against a model that's confidently wrong: keep the learned pick **advisory** — override the
  delegate only above a confidence margin, and keep `observe` running so regressions are visible.

**Out of scope:** an ML *profiler* (learning the `DataProfile` itself from raw data) — the profiler is cheap
and deterministic today; learning *selection* from the existing profile is the higher-leverage half. Revisit
a learned profiler only if feature gaps, not model quality, turn out to bound accuracy.

---

## Action Items

1. [x] **Log the training corpus — done.** `select/ObservingStrategySelector` (a `LearningStrategySelector`
   decorator) + `select/ObservationSink` append `{DataProfile features, StrategyId, SortResult}` rows via a
   stable, versionable CSV schema (`csvHeader()` / `csvRow(...)` / a thread-safe `csvSink(Appendable)`).
   Selection is the wrapped delegate's verbatim and a learning delegate still learns, so it harvests a labeled
   dataset without changing behaviour. Covered by `ObservingStrategySelectorTest`. (Pure Java; host-side
   `./gradlew build` is the gate.)
2. [x] **Offline analysis — measured.** `Phase4DecisionGate` runs a brute-force oracle (all applicable
   strategies, each metered via `SortBuffer.compareValues` + `recordMove`) over 324 workloads
   (6 sizes × 9 shapes × 2 key modes × 3 trials) and compares each selector's pick to the minimum.
   Regret = (chosen_cost − oracle_cost) / oracle_cost; oracle_cost in comparisons + moves.

   **Initial run (2026-06-21) was invalid.** `JdkSortStrategy` used the raw `b.comparator()` instead of
   `b.compareValues()`, and `b.set()` instead of `b.recordMove()`, so its measured cost was always 0.
   The oracle always picked `jdk.timsort` (cost 0), and the regret formula special-cased
   `oracle_cost == 0 → 0`, making every workload appear optimal. This was a metering bug, not a real result.

   **Corrected run after fixing `JdkSortStrategy` metering (2026-06-21):**
   Oracle sees 4 distinct winners across 324 workloads; oracle cost > 0 in all 324 workloads.

   | Selector | Exact match | Differ from oracle | Near-optimal (< 5% regret) | Mean regret | Max regret |
   |---|---|---|---|---|---|
   | `CostModelStrategySelector` | 196/324 (60.5%) | 128/324 | **196/324 (60.5%)** | **386.52%** | **6886.6%** |
   | `BanditStrategySelector` | 212/324 (65.4%) | 112/324 | **236/324 (72.8%)** | **191.94%** | **7265.4%** |

   Oracle winner spread: `counting` 138, `jdk.timsort` 108, `insertion` 48, `intro` 30.

   Worst cases for the cost model (mean regret by shape):
   - **Reversed comparable-only** (mean 1886%): cost model picks `intro`; `jdk.timsort` detects the
     reversed run in O(n) comparisons + n writeback moves — up to 68× cheaper at n=50000.
   - **Organ-pipe comparable-only** (mean 1120%): same adaptivity gap.
   - **Clustered integer-keyed** (0% exact, mean 322%): cost model misses that `counting` is near-free.

   **Real exploitable gaps exist.** Neither selector is near-optimal across the board. Items 3-5 remain open.

3. [x] **Train + export (Phase 4a) — done (2026-06-21).** `tools/phase4/` holds the offline pipeline.
   A faithful Python **mirror** (`sbs_mirror.py`: a bit-exact `java.util.Random`, the profiler feature
   computation, all eight candidate strategies' **metering**, and the brute-force oracle) is validated by
   `validate_gate.py` to reproduce the gate's exact 324-workload oracle spread
   (`counting 138, jdk.timsort 108, insertion 48, intro 30`) — so a model trained on a mirror-generated
   corpus is trained on real labels. `gen_corpus.py` emits a disjoint-by-size training grid + the 324 gate
   workloads as a held-out test set; `train_selector.py` fits a depth-limited `DecisionTreeClassifier`
   (GBT + multinomial-logistic reported as headroom) and exports **`sbs_selector_model.json`** — a stable,
   versioned (`schema_version: 1`) flat-tree blob (feature order, class names, and
   `feature/threshold/left/right/class/confidence` arrays) that an ML-free Java walker evaluates. The export
   round-trips (the JSON walker matches sklearn on all 324). For serving-time **feature parity** the Java
   `demo/Phase4CorpusDump` (`./gradlew phase4Corpus`) emits the same schema from the *real* profiler features
   (HLL distinct, sampled inversions, distribution class) — train on that corpus for the shipped model.

   **Measured on the held-out 324 gate workloads (predictions masked to feasible strategies):**

   | selector | exact-match | mean regret |
   |---|---|---|
   | `CostModelStrategySelector` (gate baseline) | 60.5% | 386.52% |
   | `BanditStrategySelector` (gate baseline) | 65.4% | 191.94% |
   | **learned tree (depth 5, 23 nodes)** | **98.1%** | **0.50%** |

   5-fold CV on the training grid: tree 96.0%, GBT 95.4%, logistic 92.2%. Top features: `key_span`,
   `inversions`, `inversion_ratio`, `distinct_ratio`. The six residual misses are all one shape —
   `sawtooth` comparable-only, oracle `jdk.timsort` (its run-detection exploits the period-8 structure) vs
   predicted `intro` — with bounded 22–32% regret, exactly the case the selector's confidence gate defers on.
   The decision tree (vs GBT/logistic) is chosen as the export: it already dominates the baselines and its
   Java loader is ~15 lines with no ML dependency. *Python pipeline runs in-sandbox; the host `./gradlew
   phase4Corpus` produces the production-parity corpus.*
4. [x] **`LearnedModelStrategySelector` (Java) — done (2026-06-21).** `select/SelectorModel` is a
   dependency-free loader for the flat model export (`sbs_selector_model.txt`, schema v1 — a line-oriented
   twin of the JSON, since the engine ships no JSON parser); it parses the parallel tree arrays with
   `String.split` and `predict(double[])` walks to a leaf. `select/LearnedModelStrategySelector` implements
   `LearningStrategySelector`, wraps a `CostModelStrategySelector` delegate, and **overrides only when**
   (1) policy is `SMART`, (2) `size >= sizeGate` (default 256 — tiny jobs aren't worth a consult),
   (3) the leaf confidence `>= confidenceMargin` (default 0.65), and (4) the predicted strategy is
   registered *and applicable* to the profile (integer-key + counting-feasibility gates) — otherwise it
   returns the delegate's plan verbatim. Its feature mapping (`DataProfile -> double[]`) is the Java twin
   of `tools/phase4/gen_corpus.features`, so training and serving see the same vector. `observe` just keeps
   a learning delegate tuning. Covered by `LearnedModelStrategySelectorTest` (override→counting/intro;
   defer below the size gate / under STABLE+FIXED / below the confidence margin / when the pick is
   inapplicable; observe-forwarding; model parse incl. version + ragged-array rejection; a real-export
   structural smoke test loading `sbs_selector_model.txt` from the test classpath). *No `DifferentialTest`
   entry: a selector is not a sort — it can only return a registry-verified strategy, so it inherits the
   differential coverage of those strategies by construction. Compile/test is the host `./gradlew build`
   gate (sandbox is JRE 11 / no CSRBT); the loader's parse+walk logic is byte-identical to the Python
   `walk_flat` that round-trips all 324 predictions.* No facade change needed — pass it to
   `BeefSort.selector(...)`.
5. [ ] **Benchmark vs the bandit** *in-JVM* (extend the JMH/selection harness); promote past the delegate only
   where it measurably wins. The **offline** half is already answered: on the gate benchmark the learned tree
   beats the bandit by +32.7 pts exact-match and cuts mean regret from 191.94% to 0.50% (item 3). The open
   part is wiring the real selector (item 4) and confirming the in-process inference cost is amortized.
6. [ ] **(Only if needed) Phase 4b:** `SbsIntelligence` gRPC service + `RemoteStrategySelector` with size gate
   + circuit breaker + local fallback, for continual/fleet-wide learning.

**Done-well metric:** a learned selector that **measurably beats `BanditStrategySelector`** on held-out
workloads, while a missing/slow/disabled model leaves selection byte-for-byte the cost-model path it is today.
