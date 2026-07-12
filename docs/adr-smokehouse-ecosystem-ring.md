# ADR: The third engine and the feeding ring — SmokeHouse, ingestion, and secondary indexes

**Status:** Proposed
**Date:** 2026-07-09
**Deciders:** Richmond
**Scope:** the whole ecosystem (CSRBT + SuperBeefSort + a new storage engine)
**Working name:** *SmokeHouse* — where beef is preserved for the long term. Provisional; alternatives
considered: BeefStore (plainer), ColdStore (more industrial). Rename is a find/replace away.

---

## Context

The ecosystem today is two engines and a nervous system. **CSRBT** is the adaptive ordered index:
pluggable balancing strategies, a control plane that senses workloads and morphs/promotes through
anti-thrash gates, ensembles with failover and healing, order statistics, windows, health-gated
persistence, an evolution machine, and full observability. **SuperBeefSort** is the intake tract:
it profiles data, picks sorts, constructs born-optimal sets in O(n), and now drives every
adaptation tier live (autopilot, aquarium, percentile service).

After twelve integration gaps, two hardening passes, and two design follow-ups, CSRBT's remaining
hunger is **structural, not incremental**. Everything it has ever eaten is *naked keys in JVM
memory*. Two absences define the next ring:

1. **It cannot hold values.** CSRBT is a set. It can order the world but not store it. Every
   real system that would want this index has records, not keys.
2. **It cannot eat the outside world.** No file, no network stream, no other process feeds it.
   Even the aquarium synthesizes its food. The external merge sorter can spill to disk but only
   *starts* from a Java `List`.

Two seams also remain under-fed: **augmentation** (`IntervalAugmentor` has never had a real
consumer) and **multi-ordering** (ensembles mirror one ordering; nothing exploits several
orderings over the same data).

Constraints carried forward from both codebases' traditions: zero runtime dependencies, Java 17,
caller-cadenced control loops (autopilot when a clock is needed), single-writer data structures
with torn-read-free reads, deterministic seeded tests, loopback-only demo servers, honest
documentation of what doesn't work.

## Decision

Build the next ring as **four composed workstreams around one new engine**:

1. **SmokeHouse** — a log-structured record store where CSRBT is the adaptive primary index and
   SuperBeefSort is the recovery and compaction engine.
2. **Ingestion feeders** — file sources (CSV/JSONL/binary) flowing through the external merge
   sorter, so both CSRBT and SmokeHouse can bulk-eat datasets bigger than memory.
3. **Secondary and interval indexes** — multiple CSRBT orderings over SmokeHouse's records,
   including an `IntervalAugmentor`-backed index, finally feeding the augment seam.
4. **Composition** — the feeders are SmokeHouse's import path; the indexes are SmokeHouse
   features; the trace replayer feeds all of it reproducibly.

```
                         ┌─────────────────────────────────────────────┐
                         │                 SmokeHouse                  │
   files ──┐             │  ┌──────────────┐      ┌─────────────────┐  │
   CSV     │  ingestion  │  │  segment log │      │ CSRBT primary   │  │
   JSONL ──┼────────────▶│  │ (append-only │◀────▶│ index (adaptive,│  │
   traces  │  feeders    │  │  values+WAL) │ get  │ autopiloted)    │  │
   ────────┘             │  └──────┬───────┘      └───────┬─────────┘  │
                         │         │ recovery/compaction  │            │
                         │         ▼                      ▼            │
                         │  ┌──────────────┐      ┌─────────────────┐  │
                         │  │ SuperBeefSort│      │ secondary +     │  │
                         │  │ (scan→sort→  │      │ interval CSRBTs │  │
                         │  │  bulk build) │      │ (multi-ordering)│  │
                         │  └──────────────┘      └─────────────────┘  │
                         └─────────────────────────────────────────────┘
```

**Where the code lives:** a new sibling repository `SmokeHouse`, composite-building against both
(`../CSRBT`, `../SuperBeefSort`) exactly as SuperBeefSort composes CSRBT today. Engines are repos
in this ecosystem; a module inside SuperBeefSort would blur the feeder/store identity and tangle
release cadences. The ingestion feeders and trace replayer land in SuperBeefSort (they are feeding
apparatus); the index coordinator lands in SmokeHouse (it is store behavior).

---

## Workstream 1 — SmokeHouse: the record store

### D1: Storage layout

| Option | Complexity | Fit with the ecosystem |
|---|---|---|
| **A. Append-only segment log + in-memory CSRBT index (Bitcask model)** | Low | The index is the star — which is the whole point |
| B. LSM tree (memtable + SSTables) | High | SSTables carry their own indexes; CSRBT becomes redundant decoration |
| C. On-disk B-tree pages | High | Reinvents CSRBT's own B+tree engine, badly, on disk |

**Chosen: A.** Values append sequentially (fast, crash-friendly, no in-place mutation); the entire
key→location map lives in CSRBT, which is exactly the component this ecosystem exists to
exercise. The classic Bitcask trade — all keys must fit in memory — is *acceptable and honest*:
SmokeHouse is an embedded store for key-indexed record data, not a general database. Document the
bound; CSRBT's `int` size cap (2³¹ keys) is the hard ceiling long before heap is.

**Record format** (one record, little-endian):
`[crc32:4][flags:1][keyLen:4][valLen:4][key][value]` — `flags` bit 0 = tombstone. CRC covers
everything after itself. A torn tail (crash mid-append) fails CRC and is truncated at recovery;
everything before it is intact by construction. Segments roll at a size threshold (default 64 MB);
closed segments are immutable.

### D2: How a set stores locations (CSRBT is `Set<K>`, not `Map<K,V>`)

| Option | Verdict |
|---|---|
| **A. `IndexEntry` element: `(key, segmentId, offset, valLen)`, comparator over key only; retrieve via `NavigableOrderedSet.floor(probe)`** | **Chosen** — public API only, one structure owns truth |
| B. CSRBT for order + side `HashMap` for locations | Splits truth; two structures to keep consistent; betrays the point |
| C. Locations in CSRBT node *tags* | Abuses a diagnostics seam; string-encoded offsets; survives morphs but ugly |

**Chosen: A.** An `IndexEntry` is value-comparable by key alone, so `floor(probe(key))` returns
the *stored* entry (with its location) iff present — retrieval through the existing public
`NavigableSet` adapter, no CSRBT changes. **Upsert** is `remove(probe)` + `add(entry)` — two
O(log n) operations under the store's writer lock; accepted cost, measured later (a
`replace` seam in CSRBT is a possible future optimization, noted, not required). **Delete** is a
tombstone appended to the log + `remove` from the index.

### D3: Durability

The log **is** the WAL — there is no second journal. `put` = append, then index update, then
return. Fsync policy is the caller's dial: `ALWAYS` (fsync per append; durable, slow),
`INTERVAL(ms)` (group fsync on a daemon; bounded loss window), `OS` (page cache; fastest,
crash-loses recent writes). Default `INTERVAL(50)` — the honest middle. The index is *never*
persisted as truth; it is always reconstructible from the log (the CSRBT snapshot below is an
optimization only).

### D4: Recovery — SuperBeefSort becomes load-bearing

Cold start: scan all segments oldest→newest, apply last-writer-wins per key (tombstones kill),
producing the live set → **sort keys with SuperBeefSort** (in-memory engine; external merge sorter
when the live set exceeds a memory budget) → **`OrderedSet.fromSorted`** — the O(n) zero-rotation
build. The demo pipeline becomes the restart path; recovery time is dominated by the sequential
log scan, not index construction.

**Warm start (optimization):** at clean shutdown, write a *hint*: the sorted `IndexEntry` list via
CSRBT's `FilePersistenceAdapter` (whose loads are already health-gated — a corrupt hint is
refused, falling back to the cold scan). Hints are advisory, never truth.

### D5: Compaction

Trigger: per-segment garbage ratio (dead bytes / total, tracked at upsert/delete time) over a
threshold (default 0.5), or manual `compact()`. Process: pick the dirtiest closed segments, read
live records (the index says which), **sort by key with SuperBeefSort**, write one new
key-ordered segment, repoint the index entries under the writer lock, delete the old segments.
Key-ordered segments make future range scans nearly sequential — compaction literally *feeds the
index and the disk layout at once*. Old segments are immutable, so concurrent reads during
compaction are safe; only the repoint needs the lock.

### D6: Index tiers

The index is a `WorkloadAdaptation`-wrapped set flown by **Autopilot** by default (the store is a
service-shaped thing; caller cadence would be a footgun). Opt-in tiers via options: plain static
RB (no adaptation), adaptive (default), **ensemble** (replicated index: O(1) failover and healing
for the index while the log stays singular), **windowed** (retention mode: the store keeps only
the newest N records; evicted index entries mark their log bytes dead for compaction).

### API (v1)

```java
SmokeHouse<K,V> store = SmokeHouse.open(dir, SmokeHouseOptions.defaults()
        .keySerializer(...)     // reuses SuperBeefSort's SpillSerializer<K>
        .valueSerializer(...)
        .fsync(Fsync.INTERVAL_50MS)
        .indexTier(IndexTier.ADAPTIVE));
store.put(k, v);   store.get(k);   store.delete(k);
store.range(lo, hi, consumer);     // streaming, key-ordered, hits the log per record
store.stats();                     // size, segments, garbage ratio, index strategy, pilot verdict
store.compact();   store.close();  // close = clean shutdown = hint write
```

## Workstream 2 — Ingestion feeders (lands in SuperBeefSort)

**`source` package.** `RecordSource<K,V>`: a streaming iterator of records with a close hook.
Implementations: `CsvSource` (column indices for key/value, header skip), `JsonlSource` (top-level
field extraction — a deliberately minimal scanner, zero-dep, documented as such), `BinarySource`
(length-prefixed, reuses `SpillSerializer`). All seeded/deterministic where randomness exists
(sampling).

**Streaming external sort.** Today `ExternalMergeSorter` starts from a `List`. Add the overload
that generates runs from an `Iterator` — read up to `runSize`, sort with the full engine, spill,
repeat — so a 100 GB CSV never materializes. Termination: `sortToList` stays list-based;
`sortAndFeed(Iterator, target, maxSize)` and a new `feed(RecordSource, SmokeHouse)` become the
out-of-core ingestion paths.

**Trace replayer (`workload` package).** A trace is a JSONL op log: `{op, key, tsMillis}`.
`TraceRecorder` wraps any adaptation facade and writes one; `TraceReplayer` reads one and drives a
facade at recorded or accelerated speed. The menagerie gains `Workloads.fromTrace(path)` — the
aquarium can then eat *real* access patterns, and every adaptive claim becomes reproducible
against a file you can commit.

## Workstream 3 — Secondary and interval indexes (lands in SmokeHouse)

**`IndexedStore` coordinator.** Wraps a SmokeHouse with N secondary CSRBTs, each ordering
*extracted attributes*: a secondary entry is `(attribute, primaryKey)` — the composite makes
entries distinct even when attributes collide (the percentile service's codec lesson,
generalized). Writes fan out under one writer lock: log append → primary index → each secondary.
This is deliberately **not** a CSRBT ensemble — ensembles mirror one ordering for redundancy;
this is *different orderings for different queries*. A new coordinator, ~200 lines, living in
SmokeHouse.

Queries: `byAttribute(name).range(lo, hi)` → secondary range scan → primary lookups → log reads.
Secondary rebuild (schema change, corruption): scan primary, extract, **sort with SuperBeefSort**,
`fromSorted` — the same recovery muscle.

**Interval index.** For records carrying `(start, end)`: a CSRBT with `IntervalAugmentor`,
answering stabbing queries ("which reservations cover 3pm?") via the augmented max-hi walk. This
is the first real consumer of the augmentation seam. Note the current augmentor is
`Integer`-bound (CSRBT documents this); v1 accepts int endpoints — epoch minutes/seconds — and a
generic augmentor is CSRBT follow-up work if it chafes.

**Consistency stance, honest:** secondaries are updated in the same critical section as the
primary (no async lag), but there is no cross-index transaction log — a crash between log append
and index updates loses nothing (recovery rebuilds *all* indexes from the log). The log is the
only truth; every index is a cache of it. That single sentence is the design.

## Workstream 4 — Composition and phasing

Each phase ships green tests and something runnable.

**Phase 1 — SmokeHouse core** (new repo): record format + segment log + CRC/torn-tail recovery,
`IndexEntry` index over `NavigableOrderedSet`, put/get/delete/range, cold-start recovery via
in-memory SuperBeefSort sort, autopiloted adaptive index, `stats()`. Tests: round-trips,
crash-simulation (truncate mid-record, reopen), recovery equivalence (store rebuilt = store
before), upsert/tombstone semantics, range correctness vs `TreeMap` oracle.

**Phase 2 — Durability + compaction:** fsync policies, hint files (health-gated), garbage
tracking, compaction with concurrent reads, windowed retention tier. Tests: hint-corruption
falls back to scan; compaction preserves every live record (oracle diff); dead-byte accounting.

**Phase 3 — Ingestion** (SuperBeefSort): `RecordSource` trio, iterator-based external sort runs,
`SmokeHouse.importFrom(source)` bulk path (sort → key-ordered segment write → `fromSorted` —
an import *is* a pre-compacted store), trace recorder/replayer + `Workloads.fromTrace`.

**Phase 4 — The index ring:** `IndexedStore` secondaries, interval index, ensemble index tier,
and the shop window: a store dashboard (aquarium-style SSE page — puts/gets/scans live, segment
map, garbage ratios, index strategy morphing under real traffic).

## Trade-off analysis (the ones that matter)

- **Bitcask-style vs LSM** is the load-bearing choice: LSM is the "serious" answer for
  write-heavy scale, but it demotes CSRBT to decoration and triples complexity. This ecosystem's
  purpose is to make the adaptive index *load-bearing*; the segment log is the smallest honest
  body that does it. If SmokeHouse ever outgrows all-keys-in-memory, that is a new ADR, not a
  patch.
- **Two O(log n) ops per upsert** (remove+add) vs adding a `replace` seam to CSRBT: start with
  the public API; measure; only then cut a seam. Same discipline as every prior gap.
- **Zero-dep JSONL parsing** is deliberately minimal (top-level string/number fields). The
  alternative — a real JSON dependency — breaks a tradition both repos have kept; documented
  limitation over hidden dependency.
- **New repo vs module**: a repo costs setup friction once; a module costs identity confusion
  forever. Repo.

## Consequences

**Easier afterwards:** CSRBT finally holds real data with real values under real durability;
every future feeding idea (network ingest, replication, the essay's benchmarks) has a store to
attach to; the percentile service gains a durable twin; recovery/compaction give SuperBeefSort a
permanent production role, not a demo role.

**Harder afterwards:** three repos to keep in composite-build lockstep; crash-safety testing is a
new discipline neither repo has needed; the all-keys-in-memory bound must be documented anywhere
SmokeHouse is pitched.

**Revisit when:** upsert profiling justifies a CSRBT `replace` seam; interval use wants generic
endpoints; anyone asks for >2³¹ keys or keys-don't-fit-in-heap (that's the LSM ADR).

## Action items

1. [ ] Create `SmokeHouse` sibling repo (composite build on `../CSRBT` + `../SuperBeefSort`), MIT, same Gradle/JDK/test stack
2. [ ] Phase 1: record format + segment log + CRC recovery + `IndexEntry` index + put/get/delete/range + SBS cold-start recovery + autopilot + oracle tests
3. [ ] Phase 2: fsync dials, hints, compaction, retention tier
4. [x] Phase 3 — ingestion + trace replay (2026-07-08): `RecordSource` trio (`CsvSource`, `JsonlSource`, `BinarySource`/`BinarySink`), iterator-based external sort (`sortToList(Iterator)` / `sortAndFeed(Iterator,…)` + `BeefSort` `toList(Iterator)` / `feedFrom(Iterator,…)`), `TraceRecorder`/`TraceReplayer` + `Workloads.fromTrace` — all in SuperBeefSort; `SmokeHouse.importInto` (ingestion as recovery) in the SmokeHouse repo. Green pending host `./gradlew build`. Deferred: the aquarium "trace" DJ-booth button (shop-window, folds into the Phase 4 dashboard).
5. [x] Phase 3.5 — the CSRBT full-extent unlock (2026-07-10): SmokeHouse stops throttling the tree it exists to showcase. **Born optimal** — recovery derives the index strategy from `accessPolicy` + the recovery sort's `DataProfile` via `StrategyAdvisor` (no more hardcoded Red-Black; non-RB `fromSorted` builds health-gated with RB fallback; WRITE_HEAVY clamped to RB in ADAPTIVE, the morph family's bound). **Profile-guided adaptation** — the recovery sort's profile + realized metrics flow into `attachProfileGuided`, the feed primes the monitor (`recordFeed`), range reads are observed, every 64th get records realized depth. **Order statistics** — `countRange`/`nthKey`/`rankOf`/`medianKey`/`percentileKey`/`firstKey`/`lastKey`, O(log n) off the RankedSet face. **ENSEMBLE tier** — mirrored RB+AVL+Splay trio, O(1) read-path promotion + failover/quarantine/heal on the pilot. **EVOLUTION tier** — the evolution machine as an index (UCB1 bandit over the policy grid on a laboratory member; observability tier per CSRBT's own ADR-011 verdict). Ensemble-backed tiers reject `retainNewest` (no per-member Evict events). Bridge additions in SuperBeefSort: raw `recordSearch`/`recordFeed` hooks on `EnsembleAdaptation` + `EvolutionAdaptation`. Oracle suite: `CsrbtUnlockTest`.
6. [x] Phase 4: ~~`IndexedStore`~~, ~~interval index~~, ~~ensemble tier~~ (✓ 2026-07-10, see item 5), ~~store dashboard~~. Auto-compaction (4.3) ✓ 2026-07-10: `compactWhenGarbageAbove` (default 0.5) — the pilot checks the closed-segment garbage ratio under the lock and runs `compact()` outside it, re-entry guarded, verdict in `stats()`. `IndexedStore` secondaries (4.1) ✓ 2026-07-10: composition over the primary — composite `(attribute, primaryKey)` entries in per-secondary CSRBT sets, two-sided sentinel probes make `byAttribute` one exact range walk (no filtering), updates retract via the documented extra read, rebuild-from-primary on open (sweep → extract → SuperBeefSort → `fromSorted`), retention refused at build time; double-oracle suite `IndexedStoreTest`. Interval index (4.2) ✓ 2026-07-10: an `interval(name, start, end)` flavor on the same `IndexedStore` builder, riding CSRBT's `IntervalAugmentor` exactly as shipped (Integer-bound, tag-driven — not fought): distinct starts are the tree keys, the tag holds the MAX end per start (an upper bound that can never prune a real match), and an exact sidecar (start → end → keys) resolves candidate starts into precisely the matching primary keys — so duplicate starts and duplicate whole spans are fully supported, which the naive one-interval-per-node tag encoding alone could not do. `stab(name, p)` / `overlapping(name, lo, hi)`, results ordered (start, end, key); extractors validated BEFORE the primary write so an inverted span rejects the put with the store untouched; brute-force stabbing oracle in `IntervalIndexTest`. Store dashboard (4.4) ✓ 2026-07-11: `demo/StoreDashboard` + `dashboard.html`, `./gradlew run` → `127.0.0.1:8079` (loopback only, the aquarium's SSE plumbing reused verbatim — bounded per-client queues, drop-oldest on slow consumers). A built-in four-regime workload (steady-churn / hot-key-overwrite / read-heavy / delete-wave, all seeded) churns a temp-dir store on a single driver thread — the single-writer contract holds; HTTP threads only read (`stats()`, `segmentStats()`) or call `compact()`, whose copy phase is off-lock by contract. The exhibit's centerpiece is the **segment map** (one bar per segment, width = bytes, red fill = garbage ledger) plus live pilot verdicts scraped from `stats()`; auto-compaction is disabled (`compactWhenGarbageAbove(0.0)`) so the *Compact now* button owns the money shot. Smoke-tested end-to-end: regime picks, pause/resume, and a live compaction reclaiming ~190 MB while the workload ran. This closes Phase 4. (The regime picker is the DJ booth in spirit; the Phase-3 deferred *trace-replay* button — playing a recorded trace through the exhibit — remains a nice-to-have, not built.)
7. [ ] Confirm or replace the name *SmokeHouse* before repo creation

---

**Succeeded by:** [`adr-ecosystem-outer-ring.md`](adr-ecosystem-outer-ring.md) (2026-07-11) — phases
5–9: measurement + performance seams, durability hardening, the tail (watchers/snapshots/generic
intervals), the replication ring, and release engineering.
