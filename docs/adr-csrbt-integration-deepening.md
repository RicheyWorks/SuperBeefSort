# ADR: Deepen the SuperBeefSort ↔ CSRBT integration (audit + next-phase plan)

**Status:** Accepted — all five gaps (1–5) implemented
**Date:** 2026-06-19
**Deciders:** Richmond (project owner)
**Supersedes scope of:** the externally-proposed "Phase 4 — Multi-Objective Hardening & Full CSRBT Leverage" audit (corrected below)
**Related:** `docs/architecture-csrbt-integration.md` (the integration design this audits against), `PROGRESS.md`

---

## Context

An external review proposed a "Phase 4" with six workstreams, asserting that CSRBT integration is *partial* — specifically that `StrategyAdvisor`, profile-driven ensemble targeting, `buildOrderedSet()`/`buildEnsemble()`, and post-feed `MorphController` wiring "aren't fully implemented yet."

**That assessment is materially out of date.** Grounded in the actual `csrbt/` package and `BeefSort` facade, most of the proposed CSRBT work already ships. This ADR (1) corrects the audit against the code, and (2) defines the *genuine* remaining gaps and a plan to close the highest-leverage ones — i.e. "integrate with CSRBT better," scoped to what is actually missing.

The guiding success metric is unchanged from the design doc (§5): **on a workload matching the declared `AccessPolicy`, SBS constructs the tree in O(n) and CSRBT's control plane HOLDs** — the tree was born right, so it never has to morph.

---

## Audit — what is actually implemented (corrected)

Mapping the integration design's rollout (`architecture-csrbt-integration.md` §Appendix) to the code on `main`:

| Designed capability | Status | Where |
|---|---|---|
| `AccessPolicy` + `StrategyAdvisor` (profile + access → `TreeStrategy`) | ✅ **done** | `csrbt/AccessPolicy`, `csrbt/StrategyAdvisor` (SKEWED→Splay, READ_HEAVY→AVL, WRITE_HEAVY→WeightBalanced, BALANCED→RedBlack) |
| O(n) born-optimal construction via `OrderedSet.fromSorted(...)` | ✅ **done** | `BeefSort.buildOrderedSet()` (advisor-/override-chosen strategy), `BulkBuildFeeder` |
| `.accessPattern()` / `.targetStrategy()` builder hooks | ✅ **done** | `BeefSort` |
| Profile-driven ensemble composition | ✅ **done** | `csrbt/EnsembleTargetFactory.forProfile(...)` (access-advised primary + RedBlack replica, `parallelFanOut`), `BeefSort.buildEnsemble()` |
| Parallel mirror bulk-load of an ensemble | ✅ **done** | `feed/ParallelFeeder` + `EnsembleOrderedSet.buildAllFromSorted` (per `PROGRESS.md` Phase 3) |
| Post-feed adaptation: `WorkloadMonitor` + `MorphController` | ✅ **done (single set)** | `csrbt/WorkloadAdaptation` (`attach` / `attachProfileGuided`, `maybeAdapt()` → `MorphController.evaluateAndMaybeMorph`) |
| "Two engines talking": sort profile primes tree adaptation | ✅ **done** | `csrbt/ProfileGuidedScorer` (multiplicative prior on the favored `StrategyId`), `BeefSort.buildCoOptimized()` / `buildAdaptive()` |
| Streaming / bounded feed + health policy | ✅ **done** | `BeefSort.streaming()`, `StreamingFeeder`, `withHealthPolicy()` |
| Drift-aware multi-batch streaming | ✅ **done** | `BeefSort.adaptiveStream()`, `stream/` package |

Net: the proposal's **Workstream 3 ("Full CSRBT integration")** is ~90% delivered, and its **Workstream 1** premise ("selectors are comparison-focused; capture moves/memory") is now also largely false — `SortResult` already carries `moves` and, as of this session, **measured `peakAuxBytes`**; `StrategyCapabilities` carries `stable` + an `AuxMemory` class; the cost-model/bandit honour an auxiliary-memory budget (SMART + STABLE), and `BanditStrategySelector.costWithMemory(w)` lets the bandit learn on `comparisons + moves + w·peakAuxBytes`. Building those from scratch would be re-doing shipped work.

### Honest status of the other proposed workstreams (so the plan doesn't chase phantoms)

- **WikiSort "fate" (WS2):** the duplicate-tolerant block merge is now *done* — `WikiSortStrategy` was replaced with a faithful no-cache WikiSort port that handles duplicates natively (O(n log n) moves, O(1) aux), superseding the per-merge `blockMergeDup` ADR. The remaining open question is the *empirical* one the proposal correctly raises: it is still ~2–3× slower in wall-clock (higher comparison constant), so it stays gated behind STABLE + large + mostly-distinct. **Keep**, but make the value defensible with the move/memory benchmark below — do **not** delete.
- **Rust kernel (WS4):** unchanged — still a PoC in `phase2-ffm/`. Out of scope for "integrate with CSRBT better"; tracked separately. Recommended: time-box a decision, but not part of this ADR.
- **Multi-objective (WS1):** foundation shipped (measured aux + memory-budgeted/`costWithMemory` selection). The remaining honest gap is **feed-time** in the objective and a single composite `CostVector`, not "capture memory."
- **Complexity (WS6):** real but mild; the selector layering (RuleBased → CostModel → Bandit, the latter delegating deterministic policies to the former) is already clean. No collapse needed; a `@Experimental`/docs pass suffices.

---

## Decision

Close the **five genuine CSRBT-integration gaps**, in priority order. Each is independently shippable and composes only CSRBT's *public* control plane (no CSRBT changes), consistent with the design doc's philosophy.

### Gap 1 — Ensemble adaptation (`EnsembleController`) is unwired *(highest leverage)*

`WorkloadAdaptation` wires `MorphController` for a **single** `OrderedSet`. There is **no** equivalent for `EnsembleOrderedSet`: the design's §4 promotion path (`EnsembleController(ensemble, monitor).evaluateAndMaybePromote(opsElapsed)` — read-path migration across differently-balanced members, plus failover / quarantine / heal / retire) is explicitly **not** implemented ("SBS does not implement promotion; it composes the member set"). So `buildEnsemble()` builds a fault-tolerant member mix but nothing lets the read path migrate to the member that matches live traffic — half the reason to pay K× for an ensemble.

**Decision:** add `csrbt/EnsembleAdaptation<K>` — the ensemble analog of `WorkloadAdaptation` — composing CSRBT's `EnsembleController` + a `WorkloadMonitor`, with `recordSearch/Add/Remove` reporting and `maybePromote()` → `EnsembleController.evaluateAndMaybePromote`. Surface it as `BeefSort.buildAdaptiveEnsemble(MorphPolicy)` (sort → `EnsembleTargetFactory` build → attach controller), mirroring `buildAdaptive`/`buildCoOptimized` for the single-set case.

### Gap 2 — Profile-driven member mixes (`EnsembleSpec` + persistent/snapshot member)

`EnsembleTargetFactory.forProfile` hardcodes one shape: access-advised primary + RedBlack replica + `parallelFanOut`. The design (§4) calls for the *profile/spec* to choose the mix — including a **persistent member** (`persistentMember()`: O(1) snapshots, wait-free readers) for snapshot/time-travel reads, and read-scaling member counts. There is no `EnsembleSpec`, so callers can't request "I need O(1) snapshots" or "scale reads across 3 shapes."

**Decision:** introduce a small `EnsembleSpec` (members, replica count, `snapshot()` flag, mode) and `EnsembleTargetFactory.forProfile(profile, access, spec)`; default spec reproduces today's mix exactly. Add a persistent member when `spec.snapshot()` (or when the profile/access implies time-travel reads). `BeefSort.buildEnsemble(EnsembleSpec)`.

### Gap 3 — Post-feed observability (`TreeEventListener` → `SortReport`)

The handoff goes dark after the feed. The design's rollout item 5 calls for "a `TreeEventListener` that folds morph/repair events into `SortReport`," and the §5 success metric ("born right ⇒ **zero** morphs") is **unmeasured**. Today `SortReport` ends at sort + feed; whether the control plane HELD or thrashed is invisible.

**Decision:** attach a CSRBT `TreeEventListener` in `WorkloadAdaptation`/`EnsembleAdaptation` that counts morphs / repairs / promotions, and extend the post-feed report (a new `AdaptationReport`, or fields on `SortReport`) with `{morphs, repairs, promotions, heldStrategy}`. This also gives the regression guardrail a metric: **assert zero morphs on a workload matching the declared `AccessPolicy`.**

### Gap 4 — Surface CSRBT's order statistics as the payoff of feeding

`OrderedSet` exposes `select · rank · successor · predecessor · median · percentile · countInRange · rangeQuery`. The design (§0) notes "SBS can surface these post-feed," but `BeefSort` returns the bare set and stops. Callers get order statistics only by knowing CSRBT's API directly — the "why feed an *ordered* structure" payoff isn't presented.

**Decision:** a thin, optional `OrderStats<K>` view (or documented recipes) returned alongside the built set, delegating to CSRBT — no new algorithms, just making the payoff first-class and tested end-to-end (sort → build → `median()`/`percentile(p)`/`rangeQuery(lo,hi)`).

### Gap 5 — Carry the sort's measured signals into the co-optimization prior

`ProfileGuidedScorer` biases the tree decision from `DataProfile` + `AccessPolicy` only. The session added a *measured* memory signal (`SortResult.peakAuxBytes`) and a memory-weighted objective, but none of it reaches CSRBT's strategy choice. This is the true "multi-objective handoff": let the realized run (not just the static profile) inform the prior.

**Decision (smallest, last) — implemented.** `buildCoOptimized` derives the `ProfileGuidedScorer` prior strength from realized run signals: a stronger prior when the sort was cheap/clean (the profiler found exploitable structure) and the disorder signal was measured exactly, weaker when it was expensive/generic or only sampled. Realized as `ProfileGuidedScorer.derivePrior(SortResult, DataProfile)` over a `[0.05, 0.25]` band centered on the previously-fixed `0.15`, so a neutral or absent run signal reproduces the old behavior exactly. See action item 5.

---

## Options considered (for Gap 1, the anchor)

### Option A — `EnsembleAdaptation` adapter composing `EnsembleController` *(recommended)*
| Dimension | Assessment |
|---|---|
| Complexity | Low–Med — mirrors the proven `WorkloadAdaptation` shape |
| CSRBT changes | None — public `EnsembleController` only |
| Risk | Low — promotion is health-gated and anti-thrash inside CSRBT |
| Symmetry | High — single-set and ensemble adaptation look identical to callers |

**Pros:** consistent mental model; reuses the report/observability plumbing from Gap 3; honours "compose CSRBT's control plane, don't reinvent it" (anti-pattern table §8). **Cons:** another adapter class; promotion semantics need an integration test against real member failover.

### Option B — Fold ensemble promotion into `WorkloadAdaptation` (one class, polymorphic on target)
**Pros:** one entry point. **Cons:** conflates two CSRBT controllers (`MorphController` vs `EnsembleController`) with different op vocabularies; the `set()` vs `ensemble()` accessor split leaks; harder to test. **Rejected** — the single-set adapter already rejects non-morph-family targets up front; an ensemble is a different target type, not a mode.

### Option C — Do nothing; document that promotion is the caller's job
**Pros:** zero code. **Cons:** leaves the ensemble's headline benefit (read-path migration) unreachable through the facade; the design doc already promises it. **Rejected.**

---

## Trade-off analysis

The priority order is deliberately **value-of-the-handoff first, breadth last.** Gap 1 unlocks the ensemble's reason for existing; Gap 3 makes the whole §5 thesis *measurable* (and is a prerequisite for the regression guardrail the proposal rightly wants); Gap 2 generalizes the member mix; Gap 4 is pure payoff-surfacing; Gap 5 is a refinement that should wait until there's a measured baseline to refine against.

Notably, the proposal's instinct to "build the multi-objective framework first" is lower-leverage *here* because the memory/moves objective already exists — the missing multi-objective piece is **feed-time and a composite `CostVector`**, which is best designed *after* Gap 3 makes adaptation/feed cost observable. Sequencing Gap 3 before any cost-vector work avoids optimizing against an unmeasured quantity.

---

## Consequences

**Easier:**
- An ensemble built by SBS actually adapts (read path migrates to the matching member; failed members quarantine/heal) — the K× cost buys its designed benefit.
- The "born right ⇒ zero morphs" claim becomes a *test*, not prose; high change velocity gets a real regression guardrail.
- Callers see order statistics as a first-class result of feeding, not a CSRBT-API scavenger hunt.

**Harder / to revisit:**
- Two adaptation adapters (`WorkloadAdaptation`, `EnsembleAdaptation`) to keep in step — mitigated by sharing the `TreeEventListener`/report plumbing.
- `EnsembleSpec` adds a (small) surface; keep its default a byte-for-byte reproduction of today's mix.
- Promotion/failover needs an integration test that deliberately fails a member — more test infrastructure than the pure-function advisors.

**Explicitly out of scope** (tracked elsewhere, not "CSRBT integration"): the Rust kernel decision, a full composite `CostVector`/feed-time objective, and WikiSort deletion (it stays; only its benchmark-justification is owed).

---

## Action items

1. [x] **Gap 3 (morph observability) — done.** `csrbt/AdaptationReport{evaluations, morphs, currentStrategy}`, accumulated by `WorkloadAdaptation` from the `MorphController.MorphResult` stream `maybeAdapt()` already returns — **no `TreeEventListener` needed**, which is leaner than this item first imagined and uses only API the repo already exercises. `adaptationReport().held()` is the zero-morph metric; `WorkloadAdaptationTest.bornRightHoldsWithZeroMorphsOnMatchingWorkload` pins that a READ_HEAVY-born (AVL) tree HOLDs under a matching read workload. *Deferred:* repair/promotion events — those need CSRBT's `TreeEventListener` / `EnsembleController` surface, which isn't visible to SBS yet (mount `../CSRBT` to implement).
2. [x] **Gap 1 (promotion) — done.** `csrbt/EnsembleAdaptation<K>` (+ `EnsembleAdaptationReport`) composes CSRBT's `EnsembleController`: `add/remove/contains` feed the monitor, `maybePromote()` does the O(1) read-path primary swap, `checkHealth()` is the failover/quarantine/heal cadence. `BeefSort.buildAdaptiveEnsemble(MorphPolicy)` builds the profile-composed ensemble and returns the wired adapter. `EnsembleAdaptationTest` mirrors CSRBT's own `EnsembleControllerTest.skewedReadsPromoteSplayWithoutRebuild` through the adapter (RB+AVL+Splay; a skewed hot-key read stream promotes the read path to Splay exactly once), and `EnsembleHealthAdaptationTest` mirrors CSRBT's `EnsembleHealthTest` (a corrupt non-primary is quarantined + healed; a structurally-corrupt primary fails over RB→AVL), so the `checkHealth()` failover path is exercised end-to-end, not just smoke-covered.
3. [x] **Gap 2 — done.** `csrbt/EnsembleSpec{Mix(LEAN|ADAPTIVE), snapshot}` drives `EnsembleTargetFactory.forProfile(…, spec)`: LEAN = access-advised primary + RedBlack replica (default, unchanged); ADAPTIVE = the promotable RedBlack+AVL+Splay morph-family trio; `withSnapshot()` adds a `persistentMember()` (O(1) snapshots). `BeefSort.buildEnsemble(EnsembleSpec)` + `buildAdaptiveEnsemble(EnsembleSpec, MorphPolicy)`; **`buildAdaptiveEnsemble(policy)` now defaults to the ADAPTIVE trio** so it promotes out of the box (the old LEAN default was often RedBlack+RedBlack, with nowhere to promote). Covered by `EnsembleSpecTest` (LEAN/ADAPTIVE/snapshot member lists; facade default is the trio).
4. [x] **Gap 4 — done.** `csrbt/OrderStats<K>` — a uniform order-statistics view: `OrderStats.of(rankedSet)` over a `buildOrderedSet()` result, `OrderStats.ofEnsemble(ensemble)` over a fed `EnsembleOrderedSet` (which exposes only `OrderedCollection`), re-resolving the primary on each call so the view follows a promotion. Surfaces `select` / `rank` / `successor` / `predecessor` / `min` / `max` / `median` / `percentile` / `countInRange` / `rangeQuery`. Covered by `OrderStatsTest` (sort → build{OrderedSet, Ensemble} → the full statistic set over a known 0..99).
5. [x] **Gap 5 — done.** `ProfileGuidedScorer.derivePrior(SortResult, DataProfile)` derives the prior strength from the realized run — a `[MIN_PRIOR=0.05, MAX_PRIOR=0.25]` band centered on the old fixed `DEFAULT_PRIOR=0.15`, from two equally-weighted facets: *cleanliness* (realized comparisons vs the `n·log₂n` baseline — a cheap sort, incl. a ~0-comparison non-comparison sort, signals exploitable structure, so the distribution read that picks the tree strategy is trustworthy) and *certainty* (exact vs sampled global-disorder, tempered for a SHALLOW pass). Exposed via `forRun(profile, access, metrics)` + a 5-arg `WorkloadAdaptation.attachProfileGuided(...)`; `BeefSort.buildCoOptimized` now passes `run.sortMetrics()`. A neutral/absent signal reproduces the historical fixed prior exactly (refinement, not behavior change), and the prior stays a nudge across the whole band. The fixed-prior `forProfile` / 4-arg paths (incl. `EnsembleAdaptation`) are untouched. Covered by `ProfileGuidedPriorTest`; `CoOptimizationTest` now exercises the derived-prior path. *Follow-up (optional):* the ensemble co-optimization prior could take the same treatment, but no facade feeds it run-metrics yet, so it stays fixed-prior for now.
6. [ ] **Doc hygiene:** fold this audit's "what's done" table into `PROGRESS.md`; mark the proposal's WS1/WS3 as largely-delivered so the next external review starts from the code, not the stale claim.

**Done-well metric (unchanged):** on a workload matching the declared `AccessPolicy`, construction is O(n) **and** the control plane reports **zero morphs** — now asserted by Gap 3's guardrail.
