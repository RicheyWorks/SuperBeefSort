# Changelog 2026-07-18 — the day the ring became an organism

One session, in order. Everything below shipped green with oracle tests and CI.

## The audit that started it

A utilization audit of the then-three repos found the builds structurally sound with four
findings, all fixed: dead `BasicDataProfiler` deleted (SuperBeefSort); jmh source sets hooked
into `check` everywhere (compile rot was invisible before); the arena replay recorders wired
as Gradle tasks with their stale `ant compile` javadocs fixed (CSRBT); a CI workflow added to
SmokeHouse. The audit's deeper finding: shipped seams with no consumers — order statistics,
then the tail, then replication — which became the engine-selection principle for everything
that followed.

## Engines 4–6 (fifth-engine ADR, accepted same day)

- **Carver** — cost-based read planner over `IndexedStore`: exact costs from CSRBT
  `countRange`/`size`, EWMA-refined priors for index paths, drive-cheapest + intersect-rest,
  `explain()`. JMH rig: planned vs oracle-best vs naive-worst.
- **Renderer** — materialized views folding the tail into CSRBT-held ranked aggregates;
  replace-idempotent fold makes subscribe-then-sweep bootstrap race-free with no snapshot
  fencing; gapped views fail loudly.
- **Brine** — read-through cache whose eviction policy is evolved per workload by
  `csrbt-experimental`'s cache loop (its first external consumer — ADR-013 §4's publication
  trigger fired); invalidation rides the tail; same-seed-same-lineage determinism tested.
  JMH rig: evolved vs fixed policy vs no cache.

## SmokeHouse phases landed

- **Phase 7 completion:** generic (typed) interval endpoints — `.interval(name, order,
  start, end)` tier over `GenericIntervalAugmentor`, typed `stab`/`overlapping`, oracle in
  `GenericIntervalIndexTest`; Carver gained matching typed span predicates.
- **Phase 8:** tail-shipped read replicas — `ReplicationServer` (subscribe → buffer → backup
  → ship → go live) + `Replica` (restore + apply + lag), bootstrap-baseline fix found by the
  first red test run, four-way oracle in `ReplicationTest`. Non-goals on the record.
- **Phase 9 slice:** `maven-publish` + POMs across every artifact — the whole ring installs
  with `publishToMavenLocal`. Central release remains the one open roadmap item.

## Engines 7–11 (seventh-engine ADR, accepted revised, by decree)

- **PitBoss** — fleet conductor: `tick()` lag/gap policy, cold-start re-bootstrap, audited
  promotion runbook; decides nothing, does everything.
- **DryAge** — vault of backup generations; `asOf` opens the past as a full read-only
  SmokeHouse on a scratch copy; preserve is the shutter.
- **Twine** — crash-atomic batches: journal → fsync → atomic rename (the commit point) →
  apply → replay-at-open; all three crash windows tested. Revised the ADR's own verdict —
  composition sufficed.
- **SmokeSignal** — loopback wire: GET/PUT/DELETE/SIZE/COUNT_RANGE over `SpillSerializer`
  framing; wire ≡ direct store calls, proven; loudly not a network service.
- **Jerky** — DEFLATE + CRC cold archives of backups; verify-before-extract; compressed-size
  framing (the inflater read-ahead lesson, caught in review); columnar keeps its
  measured-first trigger.

## Docs

Every README in all eleven repos: consistent badges, per-engine design notes, the full
eleven-row ecosystem table. New: [`ECOSYSTEM.md`](ECOSYSTEM.md) — the organism mapped, with
the nine doctrines every engine obeys. Two engine-selection ADRs written and accepted.

## Still open

The Maven Central release (signing + credentials — a deliberate decision), the two JMH
verdict runs (Carver plan quality; Brine evolved-vs-fixed), and the replica panel on the
shop-window dashboard (Phase 8's demo garnish).
