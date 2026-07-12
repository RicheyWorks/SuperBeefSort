# ADR: The outer ring — measurement, hardening, the tail, replication, and release

**Status:** Proposed
**Date:** 2026-07-11
**Deciders:** Richmond
**Scope:** the whole ecosystem (CSRBT + SuperBeefSort + SmokeHouse), phases 5–9
**Predecessor:** [`adr-smokehouse-ecosystem-ring.md`](adr-smokehouse-ecosystem-ring.md) (phases 1–4, closed 2026-07-11)

---

## Context

The feeding ring is closed. SmokeHouse holds real records under real durability, CSRBT is
load-bearing as its adaptive primary index (plus secondaries and an interval index), SuperBeefSort
is its recovery, compaction, and import engine, and the shop window puts the whole organism on
exhibit. Every phase-1-to-4 box is checked; the suite is 48/48 green.

What remains is everything the last ADR *deliberately deferred*, plus everything it made possible:

1. **Unmeasured seams.** Upsert is `remove` + `add` — two O(log n) descents where one might do.
   The predecessor's discipline was "start with the public API; measure; only then cut a seam."
   Nothing has been measured. SmokeHouse has no benchmark suite at all.
2. **Untested crash windows.** Recovery is oracle-tested against clean truncation, but the
   compaction crash windows documented in the predecessor have never been *fuzzed* — no harness
   injects a kill at every fsync/rename boundary and proves reopen-equals-oracle.
3. **No way out of the process.** The log is the only truth, but the truth can't leave the
   machine: no backup, no restore, no replica. For a store whose whole design brags that
   everything rebuilds from segments, shipping segments is the natural next organ.
4. **A read surface frozen at request/response.** No snapshot iteration, no watchers. And the
   interval index is still `Integer`-bound — the predecessor's own "revisit when" hook.
5. **Three repos nobody can `mvn install`.** No CI, no published artifacts, and the benchmark
   essay the predecessor promised ("the essay's benchmarks... has a store to attach to") does not
   exist.

Constraints carried forward unchanged: zero runtime dependencies, Java 17, single-writer data
structures, caller-cadenced control loops (autopilot where a clock is needed), deterministic
seeded tests, loopback-only demo servers, honest documentation of what doesn't work.

## Decision

Build the outer ring as **five phases ordered by dependency**, each shipping green tests and
something runnable:

```
Phase 5            Phase 6              Phase 7             Phase 8              Phase 9
MEASURE       →    HARDEN          →    THE TAIL       →    REPLICATE       →    RELEASE
JMH suite,         crash fuzzing,       tail API,           segment-shipping     CI, Maven
replace seam,      manifest,            watchers,           read replicas,       Central,
mmap reads         backup/restore       snapshots,          replica dashboard    benchmark
                                        generic intervals                        essay
```

The ordering is the argument: you don't cut seams you haven't measured (5 before everything);
you don't ship bytes off-machine before the on-machine bytes survive fuzzing and have a manifest
naming them (6 before 8); watchers and replicas are both *consumers of one log-tail primitive*,
so the tail is built once, first, and replication rides it (7 before 8); you publish when the
API stops moving, not before (9 last).

---

## Phase 5 — Measurement and the performance seams

**JMH for SmokeHouse.** A `smokehouse-benchmarks` source set mirroring CSRBT's `csrbt-benchmarks`
module: put/get/upsert-heavy/range/recovery/compaction benchmarks, seeded and shape-parameterized
(the profiler's distribution classes reused as workload shapes). These numbers gate every
decision below and feed the Phase 9 essay. **Nothing else in Phase 5 lands without a number
justifying it.**

### D1: The CSRBT `replace` seam

| Option | Verdict |
|---|---|
| A. `replace(probe, entry)` on the `NavigableOrderedSet` adapter: one descent, swap the element in place when compare-equal | **Not cut** — JMH said no (see the measured verdict below); the key is unchanged so it *would* be free of rebalance, but the win is too small to matter |
| **B. Keep `remove` + `add`** | **Chosen** — remove+add is only ~15% of end-to-end upsert; the benchmark did not beat it by enough to matter |
| C. A full `Map` face on CSRBT | Scope explosion; SmokeHouse already proved the set face suffices |

The seam is *compare-equal element replacement*: legal precisely because the comparator ignores
the location fields. It must be added at the adapter level (public API), survive morphs
trivially (it never touches structure), and be rejected loudly for elements that don't
compare-equal to the incumbent.

**Measured (Phase 5 JMH, n = 100 000, Red-Black — SmokeHouse `IndexUpsertBenchmark` +
`StoreOpsBenchmark`):** the index-only `remove` + `add` costs **817 ns/op** against an end-to-end
`upsert` of **~5 000–5 560 ns/op** — i.e. **~15%**, landing right on the gate, with the log append +
fsync dominating exactly as predicted. A `replace` (one descent + an O(1) swap ≈ `locate`'s 138 ns)
would save ~670 ns: a **~12% speedup on overwrites only**, nothing for fresh inserts, gets, or
ranges. That does not justify a permanent public `replace` surface that every future morph strategy
must honor. **Option B stands, the seam is not cut** — the predecessor's discipline, kept.

### D2: mmap reads for closed segments

`FileChannel.map` on immutable closed segments (the active segment stays on seek+read — it
grows). Zero-dep, JDK-only. Benchmark random `get` and range scans against the current
seek+read before adopting; document the address-space cost. Unmap-on-compaction uses the
documented-safe pattern (close the channel, drop the reference, let GC unmap — no
`sun.misc.Unsafe`).

**Phase 5 signal (now motivated, not speculative):** the baseline has `get` (~6.7 µs) *slower* than
`upsert` (~5.6 µs) — the log read outweighs the append, and the read path allocates a fresh
`InputStream` / `DataInputStream` / `BufferedInputStream` per call. So D2 — or simply eliminating
that per-read allocation — is the empirically strongest lever Phase 5 turned up, ahead of D1's seam.

### Deferred, explicitly

The off-heap `MemorySegment` SortBuffer / Rust kernel idea (SuperBeefSort's long-standing
"Phase 2" note) stays deferred: it is a new ADR *when a benchmark shows the JVM buffer is the
bottleneck*, which nothing yet suggests.

## Phase 6 — Durability hardening

**Crash-fuzz harness.** A seeded harness that runs a scripted workload against a store while
injecting kills at *every* interesting boundary — mid-append (torn tail), between append and
index update, mid-hint-write, mid-compaction (before/after the repoint, before the old-segment
delete), mid-manifest-swap — then reopens and asserts oracle equality. Deterministic: seed +
kill-point index fully reproduce any failure. This is the discipline the predecessor said
"neither repo has needed" — now it's owed.

**Manifest.** A tiny generation-numbered file naming the live segments and their CRCs, written
atomically (write-new + rename). Crucially it is **advisory, never truth** — exactly like hints:
a missing or corrupt manifest falls back to the directory scan that recovery does today. Its
purpose is to give backup and replication a *consistent set to copy*, not to become a second
authority. The invariant sentence survives: the log is the only truth; every index — and now
every manifest — is a cache of it.

**Backup / restore.** `backup(targetDir)`: fsync + roll the active segment (it becomes closed,
immutable), write a manifest generation, copy the manifest's segments. Restore is `open(dir)` —
there is nothing else, because recovery already rebuilds everything from segments; backup is
just *recovery's input, relocated*. Point-in-time = retained manifest generations naming
still-live segment sets. Oracle test: backup under live churn, restore elsewhere, diff.

## Phase 7 — The tail: watchers, snapshots, and the generic interval

**The tail primitive.** `tail(fromSequence, listener)`: an ordered, gap-free stream of committed
mutations (put/delete with key, sequence, offset). The single-writer contract makes this almost
free — the writer already serializes every mutation; the tail is a bounded ring of recent events
plus a catch-up path that *reads the log itself* for anything older (bytes at an offset never
change — the immutability the design already guarantees). Slow consumers get the aquarium
treatment: drop-oldest on the ring, forced log catch-up. Built once, consumed twice (watchers
here, replication in Phase 8).

**Watchers.** `watch(key, listener)` / `watchRange(lo, hi, listener)` riding the tail, fired
outside the store lock. The dashboard grows a live event feed from the same stream — and the
deferred Phase-3 *trace-replay DJ button* finally lands: replay a recorded trace through the
exhibit and watch the pilot react to real history.

**Snapshot reads.** `snapshot()`: freeze the current index (an O(n) `fromSorted` clone — the
recovery muscle, fourth use) over the immutable segment set as of a manifest generation.
Iteration never blocks the writer; compaction defers old-segment deletion while a snapshot pins
them (refcount per generation). Honest bound: a snapshot costs O(n) index copy at creation —
this is an embedded store, not MVCC; documented, not hidden.

**Generic interval endpoints (CSRBT).** The predecessor's "revisit when" hook, now chafing:
`IntervalAugmentor` generalizes from `Integer` to `Comparable` endpoints, unlocking epoch-millis
longs in SmokeHouse's interval index. Backward-compatible; the int-bound path stays as the
specialization. Lands in CSRBT with its own oracle tests before SmokeHouse consumes it.

## Phase 8 — The replication ring

### D3: Replication model

| Option | Complexity | Verdict |
|---|---|---|
| **A. Single-writer primary + N read replicas, tail-shipped** | Low | **Chosen** — bootstrap = backup/restore; catch-up = tail; replica apply = recovery, incrementally |
| B. Multi-writer / consensus (Raft) | Very high | A different project; explicitly out of scope, its own ADR if ever |
| C. Primary failover (replica promotion) | Medium | Deferred — promotion needs fencing and epoch reasoning; noted as the successor ADR's first question |

**Chosen: A, honestly bounded.** A replica bootstraps from a backup (Phase 6), then consumes the
tail (Phase 7) over a length-prefixed JDK-socket protocol (zero-dep; `BinarySource`'s framing
reused). It applies records exactly as recovery does — append to its own log, update its own
CSRBT — so a replica *is* a SmokeHouse whose writer happens to live elsewhere, and every index
tier (adaptive, ensemble, evolution) works unchanged on it. Replicas serve reads and expose
`lagSequence`. Loopback-only demo, as tradition demands: the dashboard gains a replica panel —
spawn one in-process, watch lag under churn, kill it, watch it re-bootstrap.

**Non-goals, stated loudly:** no automatic failover, no write forwarding, no consensus. A dead
primary means a manually promoted replica, and split-brain prevention is the operator's problem
in v1. That sentence goes in the javadoc.

## Phase 9 — The release ring

**CI.** GitHub Actions on all three repos: JDK 17, the composite checkout (sibling clones),
`./gradlew build`, JMH smoke (1 fork, 1 iteration — presence, not precision). CI is
infrastructure, not a runtime dependency; the zero-dep tradition governs artifacts, and it holds.

**Publishing.** `csrbt-core`, `superbeefsort`, `smokehouse` to Maven Central under
`io.github.richeyworks`, semver from `0.x` (the composite build already keeps versions in
lockstep; publishing pins them per release instead). Experimental/benchmark/demo modules stay
unpublished. Signing + the Sonatype dance is one-time setup friction, same trade as "new repo vs
module" — pay once.

**The essay.** The long-promised benchmarks piece: Phase 5's JMH numbers, the recovery-time
curve, compaction throughput, replica lag under churn — with the honest bounds inline
(all-keys-in-memory, O(n) snapshots, manual failover). Publishing numbers without the bounds
would betray the documentation tradition; the bounds are the point.

## Trade-off analysis (the ones that matter)

- **Tail-shipped replication vs consensus** is this ADR's load-bearing choice, and it is the
  Bitcask-vs-LSM choice restated: consensus is the "serious" answer, and it would demote every
  existing organ to decoration under a mountain of new machinery. Tail shipping makes the
  *existing* organs load-bearing a second time — backup is bootstrap, recovery is apply, the
  single-writer lock is the ordering authority. If someone needs failover, that is a new ADR
  with fencing in its first paragraph.
- **Manifest as advisory vs authoritative:** an authoritative manifest would speed opens and
  simplify replication handshakes, but it would be a second truth that can disagree with the
  first. Advisory costs a directory scan on corruption and keeps the invariant sentence intact.
  Same verdict as hints, for the same reason.
- **O(n) snapshot clone vs persistent/COW tree nodes:** COW nodes would give O(1) snapshots and
  would also rewrite every balancing strategy in the ecosystem. The O(n) clone reuses
  `fromSorted` and touches nothing. Embedded-store honesty over database ambition.
- **Measure-then-cut (replace seam, mmap) vs just building them:** both are probably wins, and
  "probably" is exactly what the predecessor's discipline exists to interrogate. The benchmark
  suite must exist anyway (Phase 9 needs it); sequencing it first costs nothing.

## Consequences

**Easier afterwards:** the truth can leave the machine (backup, replicas); adaptive-index claims
become reproducible numbers in public artifacts; watchers give every future reactive idea
(caches, materialized views, the percentile service's durable twin) a subscription point; crash
windows are fuzzed ground, not documented hope.

**Harder afterwards:** a wire protocol is a compatibility surface forever; snapshot pinning
complicates compaction's lifecycle; published artifacts turn "rename is a find/replace away"
into a breaking change; CI keeps three repos honest but demands they stay green in lockstep.

**Revisit when:** anyone needs failover (fencing ADR); JMH shows the JVM sort buffer is the
bottleneck (off-heap/Rust ADR); replicas want to serve stale-bounded reads with guarantees
(consistency-model ADR); >2³¹ keys or keys-don't-fit-in-heap (still the LSM ADR).

## Action items

1. [x] Phase 5: `smokehouse-benchmarks` JMH suite; baseline upsert/get/range/recovery/compaction — landed (`src/jmh`); it surfaced + got fixed a >64k-key warm-recovery ordering bug
2. [~] Phase 5: `replace` seam — **measured, not cut** (remove+add ~15% of upsert; D1); mmap / drop-per-read-allocation — **motivated** by `get` > `upsert` (D2, still open)
3. [x] Phase 6: append/torn-tail crash-fuzz (`CrashFuzzTest` — every truncation recovers to the LWW oracle; compaction-commit + hint crash windows stay covered by `Phase2Test`'s targeted tests); advisory generation-numbered manifest (`ManifestFile`, CRC-verified, atomic); `backup()`/`restore` built on it — all landed + tested
4. [ ] Phase 7: tail primitive; `watch`/`watchRange`; `snapshot()` with segment pinning; generic `IntervalAugmentor` endpoints in CSRBT; dashboard event feed + trace-replay button
5. [ ] Phase 8: replica protocol (JDK sockets, length-prefixed); bootstrap-from-backup + tail catch-up; lag metrics; dashboard replica panel
6. [ ] Phase 9: GitHub Actions across the three repos; Maven Central publishing; the benchmarks essay
7. [ ] Confirm the name *outer ring* or rename before Phase 5 starts
