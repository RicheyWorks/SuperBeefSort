# ADR: Seventh-engine candidates — the ring after the outer ring

**Status:** Accepted, revised — ALL FIVE built 2026-07-18 by decree. Two verdicts changed on
the record: **Twine** shipped as an engine after all (a journaled commit + atomic rename +
idempotent replay achieves crash atomicity by composition; the log-format group-commit for
*reader-visible* atomicity stays a future SmokeHouse phase, trigger unchanged);
**SmokeSignal** and **Jerky**'s deferrals were overridden with scopes honestly narrowed
(loopback-only protocol face, no auth/TLS/remote; archival compression only, columnar keeps
its measured-first trigger). Engines 7–11:
[PitBoss](https://github.com/RicheyWorks/PitBoss) ·
[DryAge](https://github.com/RicheyWorks/DryAge) ·
[Twine](https://github.com/RicheyWorks/Twine) ·
[SmokeSignal](https://github.com/RicheyWorks/SmokeSignal) ·
[Jerky](https://github.com/RicheyWorks/Jerky)
**Date:** 2026-07-18
**Deciders:** Richmond
**Scope:** the whole ecosystem (CSRBT + SuperBeefSort + SmokeHouse + Carver + Renderer + Brine)
**Predecessor:** [`adr-fifth-engine-candidates.md`](adr-fifth-engine-candidates.md) (Accepted — both engines shipped same day)

---

## Context

Six engines, one organism, and the outer ring is nearly closed: Phase 7 (tail, watchers,
snapshots, typed intervals) shipped and gained load-bearing consumers (Renderer, Brine);
Phase 8 (tail-shipped read replicas) shipped with its oracle suite; Phase 9 is down to the
Maven Central release. The predecessor's deferrals now re-price:

1. **Replication is the new shipped-but-unconsumed seam.** `ReplicationServer`/`Replica` have
   only test consumers — the exact condition that produced Carver (order statistics) and
   Renderer (the tail). Lag monitoring, gap recovery, and promotion are all manual.
2. **PitBoss's blocker is gone.** The predecessor deferred the fleet conductor *because* it
   composed over replication that didn't exist. It exists.
3. **The log's deepest unexploited property is time.** Every engine reads the present; nothing
   reads the past — yet the entire doctrine ("the log is the only truth") means every
   historical state is still sitting in the segments, and backup generations are already
   manifest-named and CRC'd.

Constraints unchanged: zero runtime deps, Java 17, single-writer, caller-cadenced control
loops, loopback-only demos, deterministic seeded oracle tests, nested composites, seams named
by their consumers, honest documentation of what doesn't work.

## Candidates

### 1. PitBoss — the fleet conductor ★ recommended next

*The one who runs the smokehouse floor.* Supervises one primary + N replicas as a single
organism.

- **Mechanism.** Owns a `ReplicationServer` and a set of `Replica` handles. A caller-cadenced
  `tick()` (the autopilot pattern) reads each replica's `lagSequence()`/`gapped()`, and acts
  through policy gates: re-bootstrap a gapped replica (close, wipe dir, reconnect — a cold
  start is always acceptable), flag lag beyond threshold, and run the **promotion runbook**
  as one audited operation (stop serving, close replica, reopen its dir as primary, serve
  again) — automating exactly what the Phase 8 javadoc says the operator does by hand,
  without adding failover *decisions* (a human still says "promote"; PitBoss makes the doing
  atomic and on the record).
- **Feeds.** Replication (first load-bearing consumer), backup/manifest, `stats()`, and the
  shop-window dashboard (the replica panel Phase 8 deferred becomes PitBoss's exhibit).
- **Trade-offs.** (+) Small, composes only public seams, closes Phase 8's operational gap.
  (−) Must not creep into consensus — the non-goals sentence transfers verbatim; wiping a
  replica dir needs the same care as compaction's crash windows (fuzz the re-bootstrap).

### 2. DryAge — the time-travel engine ★ recommended second

*Where cuts age until they're ready.* As-of reads over the log's immutable history.

- **Mechanism.** The log never rewrites bytes, so any historical state is a **prefix replay**.
  DryAge opens a read-only store whose recovery stops at a cutoff — a manifest generation
  (backup granularity today) or a `(segmentId, offset)` position (record granularity). The
  seam it names upstream: a bounded-recovery option on `SmokeHouse.open` ("recover up to
  position P") — recovery already walks the log; this is a stop condition, not a mechanism.
  Result: `DryAge.asOf(dir, opts, position)` → a full read-only SmokeHouse of the past
  (every index tier, order statistics, even Carver over it).
- **Feeds.** The manifest (its coordinates become time coordinates), compaction discipline
  (compaction rewrites history — DryAge makes the "compaction erases the past" trade-off
  explicit: as-of reads reach only as far back as the oldest surviving segment/backup),
  Renderer (fold a view *as of* a position for backfills).
- **Trade-offs.** (+) A genuinely new read capability priced at one upstream stop-condition.
  (−) Honest bound must stay honest: compaction + retention truncate reachable history;
  positions are physical, not timestamps (a timestamp index is future work, measured first).

### 3. Twine — atomic multi-key batches (a SmokeHouse phase, not an engine)

Cross-key atomic writes via a group-commit marker in the log (recovery honors only completed
groups). Real, wanted (the indexed-store ADR names the absence), but it changes the log
format — that is storage-engine surgery belonging inside SmokeHouse as a phase with its own
crash-fuzz battery, not a composition engine. Recorded here so nobody builds it outside.

### 4. SmokeSignal — the wire protocol (still deferred)

Unchanged verdict: a network face waits for the Phase 9 API freeze and a real transport/auth
decision. PitBoss's loopback fleet does not need it.

### 5. Jerky — columnar cold-segment compression (defer until measured)

Compress cold segments into a scan-friendly columnar form. No number anywhere says scans or
disk are the bottleneck; the ring's rule says measure first. A JMH row (scan throughput vs
segment size) is the re-arming trigger.

## Decision (proposed)

**PitBoss** as engine seven — the Carver precedent applied to the newest seam, closing Phase
8's operational loop with the smallest honest scope. **DryAge** as engine eight — it converts
the ecosystem's founding doctrine into a user-visible power, for the price of one upstream
stop condition. Twine stays a future SmokeHouse phase; SmokeSignal and Jerky keep their named
re-arming triggers.

```
                    ┌────────────── PitBoss (7) ──────────────┐
                    │  tick(): lag / gaps / promotion runbook │
                    ▼                                         ▼
   primary SmokeHouse ──ReplicationServer──▶ Replica A, Replica B, …
        │ segments (immutable history)
        └──────▶ DryAge (8): asOf(position) → read-only SmokeHouse of the past
```

## Revisit triggers

- Twine: first real consumer needing cross-key atomicity → SmokeHouse phase with crash fuzz.
- SmokeSignal: Central release done + an external (off-machine) consumer exists.
- Jerky: a benchmark showing cold-segment scan or disk footprint as a real cost.
