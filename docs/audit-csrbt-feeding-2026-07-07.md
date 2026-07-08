# Audit: feeding CSRBT everything it can eat

**Date:** 2026-07-07 · **Scope:** integration wiring between SuperBeefSort (the feeder) and CSRBT (the eater), audited from source in both repos.

> **Implementation status:** All 12 gaps are now implemented. Gaps 1–11 same day (see §4); Gap 12
> landed 2026-07-08 — `BeefSort.buildNavigableSet()` (the `TreeSet` drop-in flavor via CSRBT's
> `NavigableOrderedSet`) and `BeefSort.buildOrderedSetPersisted(name, keySerializer)` (the
> sorted-run → balanced-tree → durable-snapshot pipeline via `FilePersistenceAdapter`, reloadable
> through CSRBT's health-gated loads).

---

## 0. What CSRBT is supposed to be

CSRBT is not a tree library. A tree library gives you a red-black tree and wishes you luck. CSRBT's premise is that **the choice of data structure is a runtime decision, not a compile-time one** — and everything in the codebase is an organ serving that premise:

- **A metabolism** — the control plane (`WorkloadMonitor` → `StrategyScorer` → `MorphPolicy` → `MorphController`): it senses what the traffic is doing, scores every strategy against it, and morphs or promotes only through anti-thrash gates. It digests *operations* and excretes *decisions*.
- **Redundant organs** — the ensemble (ADR-003/005/008): several members over one key set, so adaptation is an O(1) primary swap, failure is an O(1) failover, and the member family spans strategy trees, a persistent path-copying engine, and a B+tree. RAID for indexes.
- **An immune system** — the health gate, `selfRepair`, quarantine/heal/retire. Nothing gets published unvalidated; a failed morph leaves the incumbent untouched.
- **A nervous system** — structured `TreeEvent`s, JSON export, the session recorder, the arena visualizer. Every adaptation decision is observable and replayable.
- **An R&D department** — the evolution machine (ADR-011/012): bandits and (μ+λ) populations breeding parameterized policies as live shadow members, with an honest experimental verdict on the record.

Armchairing its future, in your words: **infrastructure for data.** The trajectory is a self-operating ordered-index layer — the thing under a stream processor, a time-series store, or an analytics engine that today a human tunes ("use a B-tree here, size the page there, rebuild weekly") and that CSRBT instead senses, scores, gates, and does to itself. SuperBeefSort's role in that future is the **intake tract**: it profiles what's arriving, shapes it, and delivers it in the form the organism digests best. For CSRBT to come alive and show what it's supposed to be, the feeder must exercise the *whole* organism — not just its mouth.

## 1. What's already wired well

Credit first — the integration is real, layered, and disciplined (both sides compose public API only, no reach-ins):

- **The O(n) fast paths are fully exploited.** `BulkBuildFeeder` → `OrderedSet.buildFromSorted`; `ParallelFeeder` → `EnsembleOrderedSet.buildAllFromSorted` with the ensemble's own `MemberExecutor` fan-out. Dedup is done linearly on the sorted run, and the comparator is captured in `CsrbtTarget` so sort order and tree order can never diverge.
- **Born optimal.** `StrategyAdvisor` maps profile + `AccessPolicy` to the birth strategy; `OrderedSet.fromSorted` constructs directly into that shape.
- **Wired to adapt.** `WorkloadAdaptation` (single set, morph) and `EnsembleAdaptation` (ensemble, O(1) promotion + health cadence) correctly compose CSRBT's control plane; `ProfileGuidedScorer` priming the scorer from the sort's own profile — with prior *strength* derived from realized run metrics — is the standout "two engines talking" piece.
- **Streaming/top-N** uses the sliding window (`setMaxSize` + FIFO eviction) exactly as designed, and `OrderStats.ofEnsemble` re-resolves the primary per call so order statistics survive promotions.

## 2. The gaps — what CSRBT can eat that it isn't being fed

Ordered by how hungry the organ is.

### Tier 1 — starving signals (the control plane runs on thin gruel)

**Gap 1: The feed itself is invisible to the workload monitor.** No feeder records anything into a `WorkloadMonitor`. A bulk load is precisely the write-burst/growth signal `WorkloadFeatures.writeFraction` and `growthRate` describe, yet a tree built via `buildAdaptive(...)` starts its adaptive life with an EMPTY feature vector — the first `maybeAdapt()` cycles are blind until live traffic accumulates. *Fix:* let a feeder accept an optional `WorkloadAdaptation`/`EnsembleAdaptation` (or bare monitor) and record effective inserts during the feed. Cheap, no CSRBT changes. (`buildAllFromSorted` deliberately bypasses per-write cadence — fine; the *monitor* summary is a separate concern from write events.)

**Gap 2: Half the feature vector is permanently zero.** `WorkloadFeatures` has seven features; `meanSearchDepth` and `rotationsPerWrite` are two of the scorer's most shape-sensitive ones, and CSRBT's own javadoc calls search depth "the primary signal for how good the current tree shape is." SuperBeefSort's `observeSearch/observeAdd/observeRemove` all pass `0` — so the cost model scores strategies on read/write mix and skew alone, never on realized depth or churn. (CSRBT shares blame: `EnsembleController.contains` also records depth 0, and `OrderedSet` doesn't return per-op depth/rotations.) *Fix:* a small CSRBT seam — `contains` already walks the path in `findReadOnly`, so returning/exposing steps-walked is nearly free, and strategies know their rotation counts. Then `WorkloadAdaptation.observe*` forwards real numbers. This is the single highest-leverage joint change: it makes the scorer's opinion *empirical*.

**Gap 3: Nobody listens to the tree's nervous system.** `setEventListener` is never called anywhere in SuperBeefSort. The entire `TreeEvent` hierarchy — Insert/Evict/Morph/Repair/Quarantine/Heal/Retire/Promote/ShadowRebuild/MemoryCeiling/Trial/Lineage/Diversity — goes unconsumed, and with no listener CSRBT allocates nothing, so today the feed produces literally zero tree-side telemetry. SuperBeefSort already has `SortObserver`/`StepEventSink`; a `TreeEventListener → StepEventSink` bridge gives one observability stream covering profile → select → sort → feed → **morph/evict/heal**. It also unlocks `TreeSessionRecorder`: a recorded feed becomes a replayable arena session in CSRBT's own visualizer — the cheapest possible "watch it come alive" demo.

### Tier 2 — unfed mouths (whole subsystems no feeder targets)

**Gap 4: The ensemble builder's knobs are unreachable.** `EnsembleTargetFactory` hardcodes MIRROR + `parallelFanOut()`. Never reachable from SuperBeefSort: `mode(VERIFIED)` (quorum-verified reads — `CsrbtTarget.supportsEnsembleBulkBuild` even checks for VERIFIED, but no factory can produce one), `verifyEvery`, `shadowSampleRate` (the write-lean shadow modes), `memoryCeilingBytes`, `maxMembers`, `rebuildEvery`, and `engineMember(...)`. *Fix:* grow `EnsembleSpec` — `verified()`, `shadowed(p)`, `memoryCeiling(bytes)` (natural mapping from `BeefSort.maxAuxMemory`, which today only governs sort scratch), `rebuildEvery(n)`.

**Gap 5: The engine family is half-fed.** `persistentMember()` is used (good), but `BPlusTreeEngine` — CSRBT's ADR-008 answer for large n — is never constructed, despite the profiler knowing n exactly. A 10M-key run gets the same member mix as a 10K-key run. *Fix:* size-aware advice — above a threshold, `EnsembleSpec`/`StrategyAdvisor` adds a B+tree `engineMember`; the `RankedSet` seam means feeders need no changes.

**Gap 6: The parameterized strategy is fed defaults.** `StrategyAdvisor`'s WRITE_HEAVY case returns `new WeightBalancedStrategy<>()` — never `WeightBalancedStrategy(Δ, Γ)`, though CSRBT grew parameterization precisely so callers with knowledge could tune it, and the advisor's own javadoc marks the profile argument as "a seam for future distribution-aware refinement (e.g. tuning Δ from the key distribution)." SuperBeefSort *has* that knowledge (sortedness, distribution class, duplicate density) and doesn't use it. *Fix:* build the seam — even a two-rule mapping (heavier skew → looser Δ) starts honoring the contract.

**Gap 7: The evolution machine is completely unfed.** `PolicyBandit`, `PolicySearchController`, `PolicyEvolutionController`, `Fitness` — zero references. Poetic, since SuperBeefSort has its own `BanditStrategySelector`: two learning selectors, mirror images, never introduced. CSRBT's honest ADR-011 verdict (searched parameters don't beat the fixed family) says this isn't a performance play — but a `buildEvolvingEnsemble(...)` that runs a nursery against the live feed, with Lineage/Trial/Diversity events flowing through the Gap-3 bridge into the arena visualizer, is the flagship "show what it's supposed to be" demo. Low priority, high theater.

**Gap 8: Windowed feeds silently no-op on ensembles.** `StreamingFeeder` guards `setMaxSize` behind `supportsWindow()`, which is `OrderedSet`-only — so a bounded streaming feed into an ensemble quietly runs unbounded. *Fix (minimum):* fail loudly. *Fix (proper):* fan `setMaxSize` out to strategy-backed members, or have CSRBT lift the window to `EnsembleOrderedSet`.

**Gap 9: Ensemble health exists, but feeders can't reach it.** `HealthGatedFeeder`/`PrecisionFeeder` degrade to "zero checks" on ensembles because `CsrbtTarget`'s health hook is `SelfHealingTree`, which `EnsembleOrderedSet` doesn't implement — yet CSRBT has a *richer* ensemble health story (`EnsembleController.checkHealth`: failover, quarantine, heal-from-primary, retire). *Fix:* let `CsrbtTarget.of(EnsembleAdaptation)` install `checkHealth()` as the hook, so a health-gated ensemble feed exercises quarantine/heal mid-feed.

### Tier 3 — sweeteners

**Gap 10: Augmentation.** `setAugmentor`/`IntervalAugmentor` are never touched; a feeder option to install an augmentor before bulk build would deliver trees born augmented (interval trees from a sorted run in O(n) + one re-augment pass).

**Gap 11: Drift doesn't reach the tree.** `AdaptiveStreamSorter` re-selects the *sort* strategy on drift but never tells the *tree* — the javadoc calls `WorkloadAdaptation` complementary, but nothing connects them. A drift verdict is exactly when the tree's regime changed too: optionally trigger `maybeAdapt()`/`maybePromote()`, or re-prime a `ProfileGuidedScorer` from the new regime's profile.

**Gap 12: Persistence and the drop-in adapter.** `FilePersistenceAdapter`/`KeySerializer` (a sort-then-persist pipeline ending in a snapshot on disk) and `NavigableOrderedSet` (returning a fed set as a `NavigableSet` for `TreeSet` call-sites) are unreferenced. Both are adoption features more than integration features.

## 3. Suggested order of work

1. **Gaps 1–3 together** (one PR each side): feed-aware monitors, real depth/rotation signals (small CSRBT seam), and the TreeEvent bridge. This is what makes the adaptive claims *observable and empirical* rather than latent.
2. **Gaps 4–5**: `EnsembleSpec` growth + size-aware B+tree membership — pure SuperBeefSort, unlocks most of the unreached ensemble surface.
3. **Gaps 8–9**: streaming/health correctness on ensembles (Gap 8's silent no-op is the only outright bug-shaped finding in this audit).
4. **Gap 6**, then **11**, then **7/10/12** as appetite allows — Gap 7 being the showcase, not the substance.

## 4. Implementation map (landed 2026-07-07)

**CSRBT (additive seam, `docs/CHANGELOG-2026-07-07-workload-signal-seam.md`):** `MutableTree.onRotation()`
default hook fired from the shared `TreeStrategy` rotation bodies; `RedBlackTree.rotationCount()`;
`OrderedSet.rotationCount()` and `OrderedSet.searchDepth(K)` (one walk → containment + realized depth,
`~depth` when absent). Test: `test/core/RotationDepthSeamTest`.

**Gap 1 (feed → monitor):** `CsrbtTarget.observedBy(WorkloadMonitor)` records every effective insert and
bulk-built key; `WorkloadAdaptation.monitor()` / `EnsembleAdaptation.monitor()` expose the monitor;
`WorkloadAdaptation.recordFeed(...)`; `BeefSort.buildAdaptive/buildCoOptimized` record the fed run, and
`buildAdaptiveEnsemble/buildCoOptimizedEnsemble` now attach the adaptation *before* the feed and route the
feed through its monitor.

**Gap 2 (real signals):** `WorkloadAdaptation` gained data-plane facade ops — `contains` (records realized
depth via `searchDepth`), `add`/`remove` (record realized rotation deltas). `observe*` conveniences retained,
documented as the zero-signal fallbacks.

**Gap 3 (event bridge):** new `csrbt/TreeEventBridge` (`lifecycle` = adaptation events only; `verbose` = all)
forwarding onto `SortObserver` as the new `SortEvent.Type.TREE_EVENT`; `BeefSort` auto-registers it on every
built OrderedSet/ensemble when an observer is set.

**Gaps 4+5 (ensemble knobs + engine family):** `EnsembleSpec` grew `verified()/verified(stride)`,
`sampledShadow(p)`, `rebuildShadow(ops)`, `withMemoryCeiling(bytes)`, `withMaxMembers(k)`, and an
auto-on `largeNEngine` knob; `EnsembleTargetFactory` applies all of them and adds a `BPlusTreeEngine`
member when the profiled n ≥ `LARGE_N_THRESHOLD` (2²¹).

**Gap 6 (WB tuning):** `StrategyAdvisor.weightBalancedFor(profile)` — disordered feeds (sortedness < 0.5)
are born WB(4,2), calm feeds keep the literature WB(3,2).

**Gap 8 (bug):** `StreamingFeeder` now throws `IllegalArgumentException` on a bounded feed into a
windowless target instead of silently streaming unbounded.

**Gap 9 (ensemble health):** `CsrbtTarget.withHealthHook(BooleanSupplier)` +
`EnsembleAdaptation.healthHook()` — health-gated/precision feeders can now run the controller's
failover/quarantine/heal cadence mid-feed.

**Gap 10 (augmentation):** `BeefSort.augmentor(...)` — built sets are born augmented.

**Gap 11 (drift → tree):** `AdaptiveStreamSorter.Builder.adaptTree(WorkloadAdaptation)` +
`BeefSort.adaptiveStream(adaptation, maxSize)` — feed traffic reaches the tree's monitor, and every fired
drift verdict hands the tree one policy-gated `maybeAdapt()`.

**Gap 7 (evolution feed):** new `csrbt/EvolutionAdaptation` — the third adaptation tier. Two flavors
mirroring CSRBT's controllers: `banditSearch` (V3 — UCB1 over `PolicyBandit.boxGrid()`, one arm per
cycle on a laboratory member) and `population` (V4 — (μ+λ) generations bred across an exact-shadow
nursery, `defaultFounders()` = WB(3,2) + two in-box neighbors). Caller-cadenced `beginCycle()`/
`endCycle()` with the traffic since open as the policy clock; results flattened to `CycleResult`;
`observeWith(observer)` streams Trial/Lineage/Diversity through a verbose `TreeEventBridge` — the feed
the arena visualizer replays. Hosts come from `EnsembleTargetFactory.evolutionHost(...)` (throne +
laboratory slots; MIRROR for bulk-loadable bandit hosts, SAMPLED_SHADOW@1.0 for nurseries, CSRBT's
canonical setup). `BeefSort.buildEvolvingEnsemble(policy)` and
`buildEvolvingEnsemble(policy, founders, mu, lambda, seed)` do the whole dance: sort → host → bridge →
feed-into-monitor → adaptation. Honesty clause carried into the javadoc: per CSRBT's own ADR-011
verdict, this tier is selection made observable, not a promised speedup.

Tests: `FeedingSeamsTest`, `EvolutionFeedTest` (SuperBeefSort), `RotationDepthSeamTest` (CSRBT).
None were built in this session (sandbox is JRE 11; both repos need JDK 17+) — run `gradlew build`
host-side, CSRBT first (SuperBeefSort composes it as a sibling).

**All closed (2026-07-08):** Gap 12 landed as `buildNavigableSet()` + `buildOrderedSetPersisted(...)`,
and both CSRBT-side follow-ups landed per `CSRBT/docs/CHANGELOG-2026-07-08-ensemble-window-depth.md` —
ensemble reads now record real search depths where a single authoritative walk exists (never voted),
and all-strategy ensembles support the sliding window, so bounded streaming/external feeds can target
them (`CsrbtTarget` routes the window; the loud rejection now fires only for genuinely windowless
mixes, e.g. `withSnapshot()`).

## 5. Verification notes

Every "never used" claim was checked by import/symbol sweep over `SuperBeefSort/src/main/java` (e.g. `setEventListener`, `engineMember`, `verifyEvery`, `shadowSampleRate`, `memoryCeilingBytes`, `BPlusTreeEngine`, `PolicySearchController`: zero hits). Zeroed signals confirmed in `WorkloadAdaptation.observe*` (all pass 0) and `EnsembleController.contains` (depth 0). The Gap-8 silent no-op is the `maxSize > 0 && target.supportsWindow()` guard in `StreamingFeeder.feed` against `CsrbtTarget.supportsWindow()` returning `orderedSet != null`. Builds were not run (sandbox JRE constraint per CSRBT/CLAUDE.md); all findings are static-analysis-level and none depend on runtime behavior.
