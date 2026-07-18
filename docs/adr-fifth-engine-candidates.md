# ADR: Fifth-engine candidates — what plugs in next

**Status:** Proposed
**Date:** 2026-07-18
**Deciders:** Richmond
**Scope:** the whole ecosystem (CSRBT + SuperBeefSort + SmokeHouse + Carver)
**Predecessors:** [`adr-smokehouse-ecosystem-ring.md`](adr-smokehouse-ecosystem-ring.md) (phases 1–4),
[`adr-ecosystem-outer-ring.md`](adr-ecosystem-outer-ring.md) (phases 5–9)

---

## Context

Four engines, one organism: **CSRBT** orders, **SuperBeefSort** feeds, **SmokeHouse** preserves,
**Carver** decides how to read. The 2026-07-18 utilization audit found the ring structurally
closed — every seam the first two ADRs named now has a consumer (order statistics feed Carver's
cost model; the interval augmentor — including the new generic-endpoint tier — feeds the
IndexedStore; the tail primitive, watchers, snapshots, backup, and the manifest all exist on
SmokeHouse's public surface).

Two facts set up the next ring:

1. **The tail has only demo-grade consumers.** `tail`/`watch`/`watchRange` shipped in Phase 7,
   but nothing load-bearing rides them — the same "shipped seam, no real eater" state the
   order-statistics surface was in before Carver. The lesson of Carver is that a seam without a
   consuming engine rots politely.
2. **`csrbt-experimental` still has no external consumer.** ADR-013 §4 holds its publication
   behind exactly that trigger. The arena, ecology, viability map, and **cache evolution**
   machinery are green-tested research surfaces waiting for a production caller.

Constraints carried forward unchanged: zero runtime dependencies, Java 17, single-writer data
structures, caller-cadenced control loops, the-log-is-the-only-truth / every-index-is-a-cache,
deterministic seeded double-oracle tests, loopback-only demo servers, nested composite builds,
honest documentation of what doesn't work.

## Candidates

### 1. Renderer — the materialized-view engine ★ recommended next

*Where drippings are collected and rendered down.* Incrementally maintained derived views over
a SmokeHouse store, driven by the tail.

- **Mechanism.** A `View` is a fold over `TailEvent`s: group-by extractor → aggregate (count,
  sum, min/max, top-k, sliding-window percentile — the percentile demo productized), state held
  in CSRBT trees (order statistics give ranked/percentile reads on every view for free).
  Registration replays from `tailSequence` zero via the log catch-up path, then rides the live
  ring; `onGap` forces re-fold — **a view is a cache, rebuildable from the log**, so the
  ecosystem's one doctrine covers it with no new persistence.
- **Feeds.** The tail (first load-bearing consumer), CSRBT order statistics per view, Carver
  (views become queryable sets — `Carver.over` a view's index), SuperBeefSort (bulk re-fold
  via `fromSorted` on catch-up).
- **Trade-offs.** (+) Unblocked today; no new durability story; every piece reuses an existing
  seam. (−) Fold-state memory is unbounded without windowing discipline; watcher-thread
  cadence must not violate single-writer (views own their trees; the tail listener is the only
  writer — same contract, one writer per view).

### 2. Brine — the adaptive cache engine ★ recommended second

*Where things soak before they're needed.* A read-through cache over SmokeHouse (or any
key→value read path) whose **eviction policy is selected and evolved per workload** by
`csrbt-experimental`'s cache-evolution machinery.

- **Mechanism.** Bounded cache, pluggable eviction strategies (LRU/LFU/FIFO/2Q/…) as genomes;
  the experimental arena scores policies against the observed access pattern (CSRBT's workload
  monitor supplies the skew sketch) and promotes winners through health gates — the evolution
  machine's first production job. Invalidation rides `watch`/`watchRange`: the log tells the
  cache what changed, so coherence needs no guessing.
- **Feeds.** `csrbt-experimental` (fires ADR-013 §4's publication trigger), the workload
  monitor, the tail (invalidation), Carver (hot query-result caching later).
- **Trade-offs.** (+) Gives the research surface a reason to exist in production; invalidation
  correctness is inherited from the log rather than invented. (−) Two engines' worth of novelty
  (cache + evolution-in-production) — needs Renderer-grade oracle discipline plus chaos tests;
  promotes `csrbt-experimental` to a published artifact (a release-scope decision, not just code).

### 3. PitBoss — the fleet conductor (deferred)

One autopilot over N stores: range sharding (SuperBeefSort's profiler picks split points),
rebalancing via backup/restore, replica placement. **Defer:** it composes over Phase 8
segment-shipping replication, which doesn't exist yet. Building the conductor before the
second machine is scope inversion.

### 4. Charcuterie — the schema/table engine (fold into Carver)

Named typed columns, auto-declared secondaries, a small predicate DSL. Every mechanism already
lives in IndexedStore + Carver; this is sugar, not an engine. Revisit as Carver's fluent
surface grows — a `Schema` helper in Carver, not a fifth repo.

### 5. SmokeSignal — the wire-protocol engine (deferred)

A network face (redis-like text protocol) for remote clients. Conflicts with the loopback-only
constraint until Phase 8 settles authentication/transport questions, and Phase 9's release
should freeze the API first. Revisit after both.

## Decision (proposed)

Build **Renderer** as engine five, **Brine** as engine six. The ordering is the argument again:
Renderer consumes a shipped-but-unconsumed seam with zero new infrastructure (the Carver
precedent, applied to the tail); Brine then reuses Renderer-hardened tail discipline and fires
the experimental module's publication trigger — by which time Phase 8/9 decisions (publishing,
transport) are being made anyway, which is exactly when PitBoss and SmokeSignal stop being
premature.

```
   SuperBeefSort ──feeds──▶ CSRBT ◀──indexes── SmokeHouse ──log truth
        ▲                     ▲                    │ tail
        │ recovery/compaction │ views held in      ├──────▶ Renderer (5) ──views──▶ Carver
        └─────────────────────┴── CSRBT trees ◀────┤ watch
                                                   └──────▶ Brine (6) ◀─policies── csrbt-experimental
```

## Revisit triggers

- Phase 8 replication lands → PitBoss stops being premature.
- Carver's fluent surface sprouts schema-shaped helpers → Charcuterie folds in there.
- An external consumer asks for remote access → SmokeSignal, after the Phase 9 API freeze.
