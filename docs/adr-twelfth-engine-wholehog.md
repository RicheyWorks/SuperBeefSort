# ADR: The twelfth engine — WholeHog, or what "more" means at eleven

**Status:** Proposed
**Date:** 2026-07-18
**Deciders:** Richmond
**Scope:** the whole ecosystem (engines 1–11)
**Predecessors:** [fifth-engine](adr-fifth-engine-candidates.md) ·
[seventh-engine](adr-seventh-engine-candidates.md) candidates (both Accepted, all built)

## Context

Eleven engines, each oracle-tested **in isolation** — and that qualifier is now the
ecosystem's largest untested claim. Nothing anywhere runs the organism whole. Nobody has ever
attached Renderer, Brine, a Replica, *and* a watcher to one store's tail simultaneously
(four subscribers, four independent ring buffers — plausible, unproven). Nobody has run
Twine batches under Carver queries while PitBoss re-bootstraps a replica and DryAge preserves
generations. Every cross-engine interaction is inferred from contracts, not demonstrated.

The engine-selection principle that built engines 4–11 was "a shipped seam with no consumer."
At eleven, the biggest unconsumed seam is **composition itself**.

## Options considered

### Option A: WholeHog — the integration organism ★ recommended

One repo that runs everything at once: a library of cross-engine harnesses, a
seeded oracle suite over the *composed* system, and the full-organism exhibit.

| Dimension | Assessment |
|---|---|
| Complexity | Medium — no new mechanisms, only composition |
| Risk | Low code risk, high discovery value (it exists to find seam bugs) |
| Coverage | Closes the largest actual gap: concurrent-tail-consumer and cross-engine interactions |
| Cost | One repo; depends on all engines (finally exercising the full composite chain) |

**Pros:** converts "should compose" into "does compose, seeded and asserted"; the
multi-subscriber tail question gets an answer; becomes the release gate Phase 9's Central
publish deserves; the exhibit is the ecosystem's one-command demo.
**Cons:** a test-heavy engine is less glamorous; its suite is the slowest in the ring
(bounded awaits × many threads); needs discipline to stay a consumer, never a shortcut.

### Option B: Rub — the uniform observability engine

One metrics surface over eleven `stats()`/report shapes.
**Pros:** real gap; feeds the dashboard family. **Cons:** premature — WholeHog will reveal
*which* cross-engine signals matter; building the surface first would guess them.

### Option C: Sizzle — the chaos/soak engine

Fault injection (kill threads, tear files, starve rings) across the composed organism.
**Pros:** the hardest honest test. **Cons:** needs WholeHog's harnesses to exist first —
chaos over an unbuilt composition has nothing to grip.

### Option D: Stop adding; consolidate

Run the two JMH verdicts, ship Central, write the benchmark essay.
**Pros:** all real, all pending. **Cons:** these are *tasks*, not an engine, and none of
them answers the composition question either.

## Trade-off analysis

A, B, and C form a dependency chain: WholeHog's harnesses are the substrate Rub would
instrument and Sizzle would torture. Building B or C first inverts the order the outer-ring
ADR taught ("the tail is built once, first, and replication rides it"). D is compatible with
A — consolidation tasks proceed in parallel; they don't compete for the engine slot.

## Decision (proposed)

Build **WholeHog** as engine twelve. v1 scope, honestly bounded: (1) `Organism` — a harness
that stands up one store with secondaries + intervals, Carver over it, a Renderer view, a
Brine cache, a Replica under PitBoss, a DryAge vault, Twine for writes, SmokeSignal's wire,
and Jerky on the vault — all seeded, all against one `TreeMap` oracle; (2) the
**four-subscriber tail test** (Renderer + Brine + replica + watcher, one churn, all converge);
(3) the exhibit: one `main()` that runs the whole organism and prints every engine's vitals.
Rub and Sizzle become its named successors, re-armed by what it finds.

## Consequences

- Cross-engine regressions become catchable before Central, not after.
- The suite gets slower — WholeHog's CI is the long pole, accepted knowingly.
- Every future engine gains an integration bar: joining the organism means joining WholeHog.

## Action items

1. [ ] Scaffold WholeHog (engine 12) with the full sibling composite chain
2. [ ] `Organism` harness + composed-oracle suite
3. [ ] Four-subscriber tail convergence test
4. [ ] Full-organism exhibit `main()`
5. [ ] Record findings; re-arm Rub/Sizzle triggers from them
