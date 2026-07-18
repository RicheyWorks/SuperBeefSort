# The organism, mapped

Twelve engines, one organism. Each engine is its own repository, composed by nested Gradle
composite builds — clone them as siblings and every build resolves live sources. This page is
the one map: what each engine is, how the data flows, and the doctrines every engine obeys.

## The engines

| # | Engine | Role | Born of |
|---|---|---|---|
| 1 | [CSRBT](https://github.com/RicheyWorks/CSRBT) | the adaptive ordered index — pluggable balancing, runtime morphing, ensembles, order statistics, the evolution machine | the original engine |
| 2 | [SuperBeefSort](https://github.com/RicheyWorks/SuperBeefSort) | the intake tract — profile → select → sort → feed; also SmokeHouse's recovery and compaction engine | feeding CSRBT |
| 3 | [SmokeHouse](https://github.com/RicheyWorks/SmokeHouse) | the log-structured store — the log is the only truth; tail, watchers, snapshots, backups, replicas | CSRBT couldn't hold values |
| 4 | [Carver](https://github.com/RicheyWorks/Carver) | the read planner — costs access paths, drives the cheapest, learns from actuals | order statistics had no consumer |
| 5 | [Renderer](https://github.com/RicheyWorks/Renderer) | the materialized-view engine — folds the tail into CSRBT-held ranked aggregates | the tail had no consumer |
| 6 | [Brine](https://github.com/RicheyWorks/Brine) | the adaptive cache — eviction policy evolved per workload by csrbt-experimental | the evolution machine had no production job |
| 7 | [PitBoss](https://github.com/RicheyWorks/PitBoss) | the fleet conductor — lag watch, gap re-bootstrap, the promotion runbook | replication had no consumer |
| 8 | [DryAge](https://github.com/RicheyWorks/DryAge) | the time-travel engine — as-of reads over preserved backup generations | the immutable log held unread history |
| 9 | [Twine](https://github.com/RicheyWorks/Twine) | crash-atomic multi-key batches — journaled commit, idempotent replay | the store had no cross-key atomicity |
| 10 | [SmokeSignal](https://github.com/RicheyWorks/SmokeSignal) | the wire — a loopback protocol face over the store | the store had no face beyond the JVM |
| 11 | [Jerky](https://github.com/RicheyWorks/Jerky) | cold storage — compressed, CRC-verified backup archives | backups spend their lives cold |
| 12 | [WholeHog](https://github.com/RicheyWorks/WholeHog) | the integration organism — every engine attached to one store, one composed oracle, the four-subscriber tail test | composition itself was the last unconsumed seam |

## The flow

```
  files / streams                    the write path                     the read path
  ───────────────                    ──────────────                     ─────────────
  CSV · JSONL · binary                                                  Carver (4) — plans over
        │                                                               the store's indexes
        ▼                                                                     ▲
  SuperBeefSort (2) ──sorts/feeds──▶ CSRBT (1) ◀──indexes──┐                  │
        ▲                                                  │                  │
        │ recovery / compaction                     SmokeHouse (3) ◀── Twine (9) atomic batches
        └──────────────────────────────────────────┤  segment log     ◀── SmokeSignal (10) wire
                                                   │  (only truth)
                     ┌─────────── the tail ────────┤
                     ▼                  ▼          │ backups
              Renderer (5)         Brine (6)       ├───────▶ DryAge (8) as-of reads
              live aggregates      invalidation    ├───────▶ Jerky (11) cold archives
                                                   │ replication
                                                   └───────▶ Replicas ◀── PitBoss (7) conducts
```

## The doctrines

Every engine obeys these; every CLAUDE.md restates the ones it lives by.

**The log is the only truth; every index is a cache.** Recovery rebuilds everything from
segments alone. Views (Renderer), caches (Brine), replicas, secondaries — all rebuildable,
none authoritative. DryAge and Jerky are this doctrine converted into features: archive the
log and you've archived everything; replay a prefix and you've resurrected the past.

**Single writer, one level at a time.** The store serializes mutation on one lock; each
Renderer view has one writer (the tail thread); Twine allows one in-flight batch; each
Replica has one apply thread. Concurrency is layered, never shared.

**Caller-cadenced control loops.** No engine owns a clock. Autopilots, evolution generations,
fleet ticks, vault snapshots — all run at the caller's rhythm. The only threads belong to
SmokeHouse (the tail and the wire), and every consumer states it.

**Measure before cutting.** Performance seams open only with a JMH number (the D1 replace
seam stayed closed by number; Jerky's columnar format waits on one). Carver and Brine ship
benchmarks that can falsify their own designs.

**Seams are named by consumers.** `searchDepth` (SuperBeefSort), `resident()`/`champion()`
(Brine), the bootstrap baseline (replicas), bounded recovery (DryAge, still pending) — the
core never grows surface speculatively.

**Cold starts are always acceptable; wrong data never is.** A gapped view fails loudly, a
gapped cache drops everything, a gapped replica stays consistently stale, a corrupt archive
refuses to unpack. Every engine prefers honest unavailability to guessing.

**Replays are idempotent.** Last-writer-wins upserts make overlap harmless — the same
argument, used four times: Renderer's bootstrap, replication's ship-overlap, Twine's crash
replay, DryAge's restore.

**Oracle tests, seeded and deterministic.** Every behavior is asserted against a brute-force
reference (`TreeMap`, full-scan folds, hand-written best paths). The only timing concessions
are bounded awaits where real threads exist. And since engine twelve, *composition itself*
is oracle-tested: WholeHog runs every engine over one store against one reference — a new
engine joins the organism by joining that suite.

**Honest documentation of what doesn't work.** Non-goals are stated loudly (no consensus, no
failover, no auth on the wire), negative results are published (the evolution machine's
verdict, the off-heap radix verdict), and deferred work carries named re-arming triggers.

## The records

Ecosystem-scope decisions live in this directory: the
[SmokeHouse ring](adr-smokehouse-ecosystem-ring.md) (engines 1–3, phases 1–4), the
[outer ring](adr-ecosystem-outer-ring.md) (phases 5–9), the
[fifth-engine candidates](adr-fifth-engine-candidates.md) (engines 4–6), the
[seventh-engine candidates](adr-seventh-engine-candidates.md) (engines 7–11), and the
[twelfth engine](adr-twelfth-engine-wholehog.md) (WholeHog — with Rub and Sizzle as its
named successors, re-armed by what it finds).
CSRBT-internal decisions (ADR-001…013) live in
[CSRBT/docs](https://github.com/RicheyWorks/CSRBT/tree/main/docs).
