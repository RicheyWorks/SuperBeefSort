# Phase 3 design — ensemble range-sharded parallel feed

Status: **proposed** (design only; not implemented). Author: Richmond (with Claude), 2026-06-15.
Companion to [`ARCHITECTURE.md`](ARCHITECTURE.md) §5.4/§5.6 and the [`IDEAS`](IDEAS.md) "CSRBT-native depth"
entry. This document is build-ready: it names the concrete classes, the cross-repo API contract, the
concurrency argument, the fallback, and a test plan — so the implementation session can go straight to code.

> **Why a design doc and not code yet.** The parallel feed spans two repos and introduces concurrency.
> The SuperBeefSort↔CSRBT boundary (`CsrbtTarget`) does **not** currently expose anything about an
> ensemble's internal partitioning — for an `EnsembleOrderedSet` it offers only `add(K)` and
> `comparator()` (see `CsrbtTarget.of(EnsembleOrderedSet)` → `orderedSet=null, healing=null`). So Phase 3
> requires extending CSRBT, and concurrent code should be written against a real compiler and CSRBT's
> actual ensemble internals, not blind. The crux of the work is the **contract** below.

---

## 1. Requirements

**Functional**
- Feed a single ascending sorted run (the output of SBS's sort stage) into an `EnsembleOrderedSet<K>`,
  building each ensemble member concurrently.
- Result must be identical to the sequential feed: every distinct key inserted into the correct member,
  duplicates accounted, the ensemble's in-order traversal equal to the de-duplicated sorted run.
- Degrade gracefully: if CSRBT can't expose the parallel hooks, fall back to today's `BalancedBuildFeeder`
  (the current ensemble path) with no behavior change.

**Non-functional**
- Near-linear wall-clock: O(n/p + log n) for p members/threads when shards are balanced; never worse than
  the sequential feed.
- Deterministic given the same input + parallelism degree (results identical; timing may vary).
- No data races: the design must make concurrent member builds provably independent (no locks on the hot
  path), or the win evaporates.
- Bounded resource use: a configurable parallelism degree and a size threshold below which it runs serial.

**Constraints**
- Pure Java 17 (matches the rest of the engine); `java.util.concurrent` only, no new deps.
- Composite build against sibling `../CSRBT`; CSRBT changes ship in that repo and must be pushed for CI.
- Keep the `SortFeeder<K>` seam intact: `FeedResult feed(List<K> sortedRun, CsrbtTarget<K> target)`.

---

## 2. The crux: the SBS ↔ CSRBT contract

A sorted run makes range partitioning trivial **iff the ensemble is range-partitioned** (member *m* owns a
contiguous key interval `[loₘ, hiₘ)`). Then each member's keys form a **contiguous slice** of the sorted
run, found by binary search — so we slice once and hand each member its slice. CSRBT must expose two things
it currently hides:

1. **The member boundaries** — the `p-1` splitter keys (or `memberFor(key)`), so SBS can slice the run.
2. **A per-member build that is safe to call concurrently for distinct members** — ideally an O(slice)
   bulk build (each member is itself a red-black tree that already has `buildFromSorted`).

### Proposed CSRBT additions (in `EnsembleOrderedSet<K>`)

```java
// --- range introspection ---
int memberCount();
// Ascending splitter keys, length memberCount()-1; member m owns [splitters[m-1], splitters[m]).
List<K> shardSplitters();
// Optional convenience; SBS can also derive this by binary-searching the run on shardSplitters().
int memberIndexOf(K key);

// --- concurrent, per-member bulk build ---
// Preconditions: 'ascendingDistinct' are all in member m's range, ascending, distinct, and m is empty.
// Thread-safety contract: calling buildMember concurrently for DISTINCT m is safe — it must touch only
// member m's own subtree and no shared mutable ensemble state (routing table, counters) without
// synchronization. SBS will fold per-member sizes itself.
void buildMember(int m, List<K> ascendingDistinct);

boolean supportsShardedBuild();   // false on older CSRBT -> SBS falls back
```

**If the ensemble is hash-partitioned instead of range-partitioned** (verify — see §9): contiguous slicing
doesn't apply, but the feed can still parallelize by *grouping* the run by `memberIndexOf(key)` in one O(n)
pass, then `buildMember` each group concurrently. Slightly more bookkeeping, same concurrency story. The
design below is written for the range case and notes the hash branch where it differs.

### Mirroring additions in `CsrbtTarget<K>` (SuperBeefSort side)

```java
public boolean supportsShardedBuild();                 // ensemble && supportsShardedBuild() && all members empty
public List<K> shardSplitters();                        // delegates to the ensemble
public int memberIndexOf(K key);                        // delegates
public void buildMember(int m, List<K> ascendingDistinct); // delegates; throws if not an ensemble
public int memberCount();
```

These extend the existing capability-detection style already used for `OrderedSet` bulk build
(`supportsBulkBuild()` / `bulkBuild(...)`), so the new feeder probes capability exactly the way
`BulkBuildFeeder` does today.

---

## 3. High-level design

New `FeedMode.PARALLEL` and `ParallelFeeder<K>`. Data flow for a range-partitioned ensemble:

```
        sorted, de-duplicated run  (length n)              CsrbtTarget(EnsembleOrderedSet, p members)
        ┌───────────────────────────────────────┐
        │  k0 k1 k2 ........................ k(n-1)│   shardSplitters() = [s1, s2, ... s(p-1)]
        └───────────────────────────────────────┘
                         │  slice by binary-searching each splitter in the run  (O(p log n))
        ┌──────────┬──────────┬───────── ... ──────────┐
        │ shard 0  │ shard 1  │            │  shard p-1 │
        │ [.. <s1) │ [s1..s2) │    ...     │ [s(p-1)..) │
        └────┬─────┴────┬─────┴──── ... ───┴─────┬──────┘
             │          │                        │        submit p independent tasks
             ▼          ▼                        ▼        (disjoint members -> no shared writes)
      buildMember(0)  buildMember(1)   ...   buildMember(p-1)        on a fork/join pool
             │          │                        │
             └──────────┴────────── join ────────┘
                         │  fold per-shard {inserted, duplicates} -> FeedResult
                         ▼
             validate each member (parallel) -> healthy = AND over members
```

Because members own **disjoint** key ranges and each task writes only its own member's subtree, the tasks
share no mutable state on the hot path — the parallelism is lock-free by construction. The only shared
reads are the immutable run + splitters; the only shared writes (per-shard counts) are folded after `join`.

---

## 4. Deep dive

### 4.1 `ParallelFeeder<K> implements SortFeeder<K>`

```java
public final class ParallelFeeder<K> implements SortFeeder<K> {
    private final int parallelism;          // <=0 -> ForkJoinPool.commonPool() parallelism
    private final int minRunForParallel;    // below this, just delegate to BulkBuildFeeder (serial)

    @Override public FeedResult feed(List<K> sortedRun, CsrbtTarget<K> target) {
        if (!target.supportsShardedBuild() || sortedRun.size() < minRunForParallel) {
            return new BulkBuildFeeder<K>().feed(sortedRun, target); // serial fallback (BULK or BALANCED)
        }
        long t0 = System.nanoTime();
        List<K> distinct = BulkBuildFeeder.dedupSorted(sortedRun, target.comparator()); // O(n), reuse existing
        List<int[]> shards = sliceBySplitters(distinct, target.shardSplitters(), target.comparator());
        // each shard = [from, to) over 'distinct'

        ForkJoinPool pool = poolOf(parallelism);
        List<ForkJoinTask<int[]>> tasks = new ArrayList<>(shards.size());
        for (int m = 0; m < shards.size(); m++) {
            final int member = m; final int from = shards.get(m)[0], to = shards.get(m)[1];
            tasks.add(pool.submit(() -> {
                if (to > from) target.buildMember(member, distinct.subList(from, to));
                return new int[]{ to - from };   // inserted count for this shard
            }));
        }
        int inserted = 0;
        for (ForkJoinTask<int[]> t : tasks) inserted += t.join()[0];   // propagates task exceptions
        boolean healthy = target.checkHealthParallel(pool);            // see 4.4; AND over members
        long elapsed = System.nanoTime() - t0;
        return new FeedResult(FeedMode.PARALLEL, sortedRun.size(), inserted,
                              sortedRun.size() - distinct.size(), shards.size(), healthy, elapsed);
    }
    @Override public FeedMode mode() { return FeedMode.PARALLEL; }
}
```

`sliceBySplitters` is a single pass that binary-searches each of the `p-1` splitters in `distinct`
(ascending), yielding `p` contiguous `[from, to)` ranges; empty shards are allowed (skipped at build).
Correctness rests on: **distinct is ascending**, **splitters are ascending**, and **member m owns
`[s(m-1), s(m))`** — so slice m of the run is exactly member m's keys (range case).

### 4.2 Concurrency model

- **Pool**: `ForkJoinPool.commonPool()` by default, or a sized pool from `parallelism`. We `submit`
  `p` tasks and `join` — work-stealing balances uneven shards across threads automatically. (No manual
  thread management; bounded by the pool.)
- **No locks**: tasks write disjoint members. The ensemble's shared routing structure is *read-only*
  during the feed (we don't add/remove members), so `buildMember` for distinct *m* never contends — **this
  is the contract CSRBT must honor** (§2). If CSRBT can't guarantee it, the fallback path stays correct.
- **Exception propagation**: `ForkJoinTask.join()` re-throws a task's exception wrapped; we join all,
  collect the first failure, and surface it (the feed is not "partly done" silently). A failed shard build
  leaves that member empty; the engine treats the whole feed as failed and the caller can retry serially.

### 4.3 De-duplication placement

De-dup the *whole* run once (reusing `BulkBuildFeeder.dedupSorted`, already O(n)) **before** slicing, so:
(a) duplicate accounting is global and matches the sequential feeders, and (b) each member receives an
already-distinct ascending slice — the precondition `buildMember` wants. Equal keys never straddle a member
boundary (a duplicate of key *k* is in the same range as *k*), so global dedup + slice is equivalent to
per-shard dedup but simpler to reason about.

### 4.4 Health validation

Add `CsrbtTarget.checkHealthParallel(pool)`: validate each member concurrently (`selfRepair()`/validate per
member) and AND the results. Ensembles currently expose no `SelfHealingTree` hook (`healing == null`), so
this requires CSRBT to expose per-member validation; until then `checkHealthParallel` returns `true`
(no-op), exactly as `checkHealth()` no-ops for ensembles today. Non-blocking either way.

### 4.5 Configuration (thread it like the deterministic seed)

Extend `JobSpec` the same way `randomSeed` was added: an optional `parallelism` (`OptionalInt`) and a
`minRunForParallel` (default e.g. 8_192). `BeefSort` gains `.parallelFeed(int degree)`. The engine selects
`ParallelFeeder` when `spec.feedModeOverride() == PARALLEL` **or** when the plan's feed mode is PARALLEL.
The selector may choose PARALLEL when `target` is a sharded-capable ensemble and `n` is large — but keep
the default conservative (BULK/BALANCED) until benchmarked.

---

## 5. Scale & reliability

- **Speedup**: ideal `~p` for balanced shards; real speedup bounded by the slowest shard (Amdahl). The
  dedup + slice prefix is O(n) serial — small relative to the O(n) parallel build, so the serial fraction
  is low. Expect strong scaling to `p = memberCount()` then flat.
- **Skew**: fixed member ranges + skewed data ⇒ one huge shard ⇒ poor speedup (that shard dominates).
  Work-stealing doesn't help a single oversized `buildMember`. Mitigations (future): (a) **co-design** —
  when the ensemble is being *built fresh*, choose member boundaries from SBS's profiler quantiles (the
  same empirical-CDF idea the new `LearnedSortStrategy` already uses) so shards are balanced; (b) split a
  hot shard into sub-tasks if `buildMember` supports a range sub-build. Note, don't solve now.
- **Failure**: a shard task throwing leaves its member empty and fails the whole feed (no partial-success
  ambiguity). Caller retries with the serial feeder. Document this as at-most-once per member.
- **Determinism**: output is independent of thread interleaving (disjoint writes), so results are
  deterministic; only `elapsedNanos` varies. Good for golden tests.
- **Observability**: emit `SortEvent`s per shard (started/built) so the visualizer and `SortReport` can
  show shard fan-out; `FeedResult.healthChecks` carries `shardCount` here.

---

## 6. Test plan

- **Differential**: for random + skewed + clustered inputs and member counts {1,2,4,8,16}, the ensemble
  after a PARALLEL feed must in-order-equal the ensemble after a sequential BALANCED feed (and equal the
  de-duped reference sort). Reuse the `DifferentialTest` shape battery.
- **Concurrency stress**: feed large runs repeatedly under a small pool; assert no lost/duplicated keys and
  stable size across 100s of runs (catches races if the CSRBT contract is violated).
- **Boundary keys**: keys exactly equal to splitters land in the documented member (closed-left interval);
  property test that no key is dropped or double-inserted at a boundary.
- **Fallback**: a non-sharded ensemble (or `supportsShardedBuild()==false`) routes to `BulkBuildFeeder` and
  still passes the differential test.
- **Empty shards**: member counts > distinct keys ⇒ some members empty ⇒ still correct.
- **Determinism**: same input + degree ⇒ identical `inserted`/`duplicates`/in-order across runs.
- **CSRBT side**: `buildMember` concurrency test in the CSRBT repo (its own harness) — SBS assumes it.

---

## 7. Trade-offs

| Decision | Chosen | Alternative | Why |
|---|---|---|---|
| Partition unit | Member key-range slices of the sorted run | Re-partition by quantiles | Range slices are free given a sorted run + range-partitioned ensemble; quantile re-partitioning is the *co-design* upgrade for skew (later) |
| Pool | `ForkJoinPool` (common or sized) | Manual threads / `ExecutorService` | Work-stealing balances uneven shards; no lifecycle management |
| Locking | None (disjoint members) | Per-member locks | Disjointness makes locks unnecessary — but it's a *contract* CSRBT must honor; fallback covers the gap |
| Dedup | Global, once, then slice | Per-shard dedup | Global matches existing duplicate accounting and gives each member a distinct slice |
| Default selection | Stay BULK/BALANCED until benchmarked | Auto-pick PARALLEL for big n | Avoid promising a speedup before it's measured; opt-in first |
| Build vs streaming | Batch build (this doc) | Streaming/backpressure feeder | Phase 3 also lists a streaming feeder — separate concern, separate feeder; keep this one a pure batch build |

---

## 8. Incremental rollout

1. **CSRBT first** (separate repo, separate PR): add `memberCount`/`shardSplitters`/`buildMember`/
   `supportsShardedBuild` to `EnsembleOrderedSet`, with the concurrency contract and a CSRBT-side stress
   test. Push (CI needs it).
2. **CsrbtTarget**: mirror the capability probes + delegators. No behavior change until a feeder uses them.
3. **ParallelFeeder + FeedMode.PARALLEL**: implement against the new target methods; wire into
   `BeefSortEngine.feederFor(...)` and `JobSpec`/`BeefSort.parallelFeed(...)`.
4. **Tests** (§6) + a JMH `EnsembleFeedBenchmark` (parallel vs serial across member counts) to *measure*
   the speedup before flipping any default.
5. **Docs**: fold the result into `ARCHITECTURE.md` §5.4 and flip the `IDEAS` "ensemble range-sharded
   parallel feed" entry to done.

---

## 9. Assumptions to verify against CSRBT (do this first)

- [ ] `EnsembleOrderedSet` is **range-partitioned** (member ↔ contiguous key interval). If hash-partitioned,
      switch §4.1 to the group-by-`memberIndexOf` variant (still parallel, no contiguous slicing).
- [ ] Each member is (or wraps) an `OrderedSet`/red-black tree that supports an O(n) `buildFromSorted`, so
      `buildMember` can be O(slice) rather than slice·log.
- [ ] Building **distinct** members concurrently touches no shared mutable ensemble state (or CSRBT can make
      it so). This is the load-bearing assumption — if it can't hold, Phase 3 reduces to a serial feed with
      parallel *validation* only.
- [ ] Member boundaries are **stable** for the duration of a feed (no rebalancing/morphing mid-build). If
      the ensemble morphs, take a boundary snapshot and feed under it, or quiesce morphing during the feed.
- [ ] An empty-ensemble precondition for `buildMember` (mirrors `supportsBulkBuild()` requiring an empty
      `OrderedSet`); for non-empty ensembles, fall back to balanced concurrent `add` per member (still
      lock-free across members, just not O(n)).

---

### One-line summary

Slice the sorted run along the ensemble's member ranges and build each member concurrently — embarrassingly
parallel **because** the keys are already ordered and the members are disjoint. The engineering is mostly
the **CSRBT contract** (expose boundaries + a concurrency-safe per-member bulk build) and a thin, lock-free
`ParallelFeeder`; everything else (dedup, slice, fold, fallback, config) reuses machinery the feed package
already has.
