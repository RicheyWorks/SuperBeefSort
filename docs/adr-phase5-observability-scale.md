# ADR: Phase 5 — observability + scale

**Status:** Proposed
**Date:** 2026-06-20
**Deciders:** Richmond (project owner)
**Related:** `docs/HANDOFF.md` (Phase 5 roadmap; "External / out-of-core sort not implemented"), `core/SortEvent` + `core/SortObserver` (the observability contract), `web/visualizer.html` (the existing JS/SVG visualizer), `engine/BeefSortEngine` (run generation reuse), `feed/StreamingFeeder` + `stream/AdaptiveStreamSorter` (existing bounded streaming), `BeefSort` (`maxAuxMemory` — the memory-budget precedent)

---

## Context

Phase 5 bundles two genuinely different concerns under "observability + scale":

- **Scale:** the engine sorts **in memory** — `SortBuffer` is an `Object[]` region. Bounded streaming
  (`StreamingFeeder`) and drift-adaptive streaming (`AdaptiveStreamSorter`) handle *feeding* a sliding window
  into CSRBT, but there is **no out-of-core sort**: a single logical input larger than RAM cannot be fully
  sorted today. This is the substantive, still-open architectural gap.
- **Observability:** `SortObserver` emits lifecycle `SortEvent`s (`JOB_STARTED`, `PROFILED`,
  `PLAN_SELECTED`, `SORT_COMPLETED`, feed events, `JOB_COMPLETED`). **Step-level** events (per comparison /
  swap) are deliberately *not* emitted — "gated behind a later phase so the throughput path pays nothing."
  The `web/visualizer.html` step-visualizer therefore *simulates* the per-step stream in JS rather than
  consuming the real engine's events.

These are independent decisions with very different leverage, so this ADR treats them separately and is
explicit that the **scale** half is the high-value work and the **observability** half is mostly contract +
optional polish.

Constraints: reuse the existing pipeline (don't reimplement profiling/selection/sorting); keep the
throughput path free of observability cost when it's off; and make the out-of-core path **memory-budgeted**,
consistent with the `maxAuxMemory` / aux-budget machinery already in the selectors.

---

## Decision

**Scale (primary):** add an **external merge sort** path — *run generation* that chunks the input into
memory-budget-sized pieces, sorts each with the **existing in-memory engine** (so every run still gets
profile→select→sort), and spills sorted runs to temp files; then a **k-way merge** (tournament/loser tree)
that streams the merged, fully-sorted result to a sink (a sorted file, an iterator, or the existing
`StreamingFeeder` into CSRBT). Memory-budgeted (run size = budget; merge fan-in bounded by buffer memory /
file handles; multi-pass merge when runs exceed fan-in), **stable** (ties broken by run age). Expose via the
facade (e.g. `BeefSort.external(...)`). **Distributed** sort is explicitly deferred.

**Observability (secondary):** make the deferred **step-level `SortEvent` stream real** behind an opt-in flag
(off by default → zero throughput cost), so the engine can emit the faithful per-step feed the visualizer
currently fakes; define that event set as a **typed, versioned schema** shared as the contract. Only *after*
that contract exists, consider productizing `web/visualizer.html` to **TypeScript** over it — and do so for
maintainability/real-event-fidelity, not as a rewrite for its own sake (the JS visualizer is headless-tested
and works today).

---

## Options Considered — out-of-core sort

### Option A — External merge sort (run generation + k-way merge) *(recommended)*

| Dimension | Assessment |
|---|---|
| Complexity | Med — run generation reuses the engine; the k-way merge + spill I/O is the new code |
| Reuse | **High** — each run is an ordinary `BeefSort` job; merge can feed via `StreamingFeeder` |
| Scale ceiling | **Arbitrary** — N ≫ RAM via multi-pass merge |
| Risk | Low — the canonical, well-understood out-of-core algorithm |

**Pros:** textbook-robust; reuses profiling/selection/sorting for free per run (a nearly-sorted run still
gets TimSort, an integer run still gets radix, etc.); memory budget maps directly onto run size; stability is
preserved by age-ordered tie-breaks; run generation is embarrassingly parallel and the merge is range-shardable
(ties into the Phase 3 parallel-feed work). **Cons:** new spill-file lifecycle (temp dirs, cleanup, failure
handling) and a serialization format for spilled records.

### Option B — Memory-mapped / off-heap in-place sort

`mmap` the input as an off-heap `SortBuffer` (the Phase 2 off-heap variant) and sort in place.

| Dimension | Assessment |
|---|---|
| Complexity | Med-High — off-heap buffer + mmap lifecycle |
| Reuse | Partial — reuses in-place strategies only (no extra-aux sorts) |
| Scale ceiling | **Bounded by address space / OS paging**, not truly arbitrary |
| Risk | Med — paging thrash turns a "sort" into random disk I/O |

**Pros:** no explicit merge; leans on the OS. **Cons:** not genuinely out-of-core for arbitrary N (address
space + thrashing), and relies on random-access paging — the opposite of the sequential I/O external merge
sort is designed around. A **complement** to A (good for "bigger than heap, smaller than RAM+swap"), not a
replacement. Couples to the unbuilt Phase 2 off-heap buffer.

### Option C — Distributed sort

Range-partition by sampling, sort locally per node, concatenate.

**Pros:** scales past one machine. **Cons:** needs a cluster/runtime, shuffle, fault tolerance — a different
project. **Deferred** (out of scope for this ADR; revisit only if single-node external sort proves
insufficient).

---

## Trade-off Analysis

External merge sort (A) is the right core: it is the standard answer, and uniquely it **reuses the entire
in-memory engine** as its run-generation step, so all the intelligence built over Phases 0–4 (profiling,
cost-model/bandit/learned selection, the radix/TimSort/etc. strategies) pays off per run at no extra cost.
The memory budget — already a first-class concept in the selectors (`maxAuxMemory`, the aux-budget crossover)
— becomes the run size, giving one coherent knob. B is a useful *narrow* optimization (heap-relief via the
Phase 2 off-heap buffer) but isn't true out-of-core and shouldn't be the headline; C is a separate system.

On observability, the leverage is in the **contract, not the canvas.** Making the step-level `SortEvent`
stream real (opt-in) is what turns the visualizer from a faithful *simulation* into a *view of the actual
engine*, and it's reusable beyond the visualizer (tracing, debugging, teaching captures). A TS rewrite of the
HTML is comparatively cosmetic; it should follow the typed schema, not lead. Sequencing the schema first
avoids rewriting the visualizer twice.

---

## Consequences

**Easier:**
- Inputs larger than RAM become sortable while every run still benefits from the full selection pipeline; the
  merge can stream straight into CSRBT via the existing `StreamingFeeder`, so the "sorted run → ordered set"
  story extends to out-of-core sizes.
- A real step-level event stream gives the visualizer (and any tracer) ground truth, and a typed schema makes
  the SBS↔viz contract explicit and versionable.

**Harder / to revisit:**
- **Spill lifecycle:** temp-file management, cleanup on failure/interrupt, disk-full handling, and a
  record **serialization format** (compact via `KeyEncoder` for integer keys; a caller-supplied serializer
  otherwise) — new failure modes the in-memory engine never had.
- **Merge tuning:** fan-in vs buffer memory, multi-pass thresholds, and (later) parallel/range-sharded merge —
  best measured with a JMH/IO benchmark, same "measure before defaulting" discipline as Phases 2–4.
- **Observability cost discipline:** step events must stay strictly opt-in and allocation-free when off, or
  they regress the throughput path the project has guarded since Phase 0.

**Out of scope:** distributed/multi-node sort (Option C); a full visualizer TS rewrite is *conditional* on the
typed event schema landing and a real need (maintainability / real-event fidelity), not assumed.

---

## Action Items

1. [x] **External-sort skeleton.** `external/` package: `SpillSerializer` (interface + `forLongs/forIntegers/forStrings` built-ins), `SpillFile`/`SpillWriter`/`SpillReader` (temp-file lifecycle, `deleteOnExit` safety net), `TournamentTree` (min-heap k-way merge, stable via run-index tiebreaker), `ExternalMergeSorter` (run gen + multi-pass merge + streaming feed), `ExternalSortResult` (metrics record). Chunk size is set as a direct element count (`runSize`, default 100k), consistent with the existing `maxAuxMemory` philosophy.
2. [x] **k-way merge.** `TournamentTree<K>`: min-heap over `(value, runIndex)` entries; ties broken by run index ascending (earlier chunk = earlier in input) → stable across runs. Multi-pass: `mergePasses` loops while `current.size() > maxFanIn`, merging groups of `fanIn` into intermediate spill files (deleted immediately after the next pass no longer needs them).
3. [x] **Spill serialization.** `SpillSerializer<K>` interface: `write(K, DataOutputStream)` + `read(DataInputStream): K`. Built-ins: `forLongs` (8 bytes), `forIntegers` (4 bytes), `forStrings` (DataOutputStream.writeUTF). Custom serializer for other types: supply an anonymous class. EOF signals end-of-run (no separate count header needed). Round-trip covered by the long / string / Item tests in `ExternalMergeSortTest`.
4. [x] **Facade + feed.** `BeefSort.external(SpillSerializer<K>)` returns `ExternalSortBuilder` (non-static inner class, inherits comparator / keyEncoder / selector / policy / source). Builder has `runSize(int)` + `fanIn(int)`, then terminal methods: `toList()` (materialises in RAM; testing), `feedInto(OrderedSet<K>[, maxSize])` (streams directly from the tournament tree into CSRBT without materialising the output — the out-of-core path). `ExternalSortResult{elements, runs, mergePasses, elapsedNanos}` returned by `feedInto`. Differential-tested vs JDK reference across random + all pathological shapes + multi-pass scenarios + long/string key types + STABLE-policy stability (`ExternalMergeSortTest`, 11 test methods).
5. [ ] **IO/JMH benchmark.** Run size vs fan-in vs passes; only then tune defaults. (Parallel run generation
   + range-sharded merge is a follow-on, tied to Phase 3.)
6. [ ] **(Observability) Typed step-event schema.** Promote the deferred per-comparison/swap `SortEvent`s to a
   real, opt-in, versioned stream (zero cost when off); document the schema as the SBS↔visualizer contract.
7. [ ] **(Observability, conditional) TS visualizer** over the typed stream — only after item 6, and only if
   maintainability/real-event fidelity justifies replacing the working JS page.

**Done-well metric:** an input several× RAM sorts correctly (differential vs a reference) under a fixed memory
budget with sequential spill I/O; and, with step events **off**, the in-memory throughput path is byte-for-byte
as fast as today.
