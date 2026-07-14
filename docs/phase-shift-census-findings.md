# Phase-shift census findings — read-path adaptivity does not pay (2026-07-14)

`PhaseShiftWorkTest` ran green (host, JDK 22). The question it was built to answer — does the
CSRBT self-tuning index do less work than the best static tree on a workload whose read skew
shifts in phases — came back **no**, and the *why* is more valuable than the number.

## The numbers (seed 20260712, N=100k, 8×200k reads, uniform/92%-hot alternating)

| arm | comparisons | cmp/read | verdict |
|---|---|---|---|
| CSRBT_RedBlack | 24,947,715 | 15.59 | best |
| JDK_TreeMap | 24,947,715 | 15.59 | identical to RB — harness sanity check passed |
| CSRBT_AVL | 25,334,791 | 15.83 | +1.6% |
| CSRBT_ADAPTIVE | 27,107,383 | 16.94 | **+8.7% — loses** |
| JDK_SkipList | 42,129,498 | 26.33 | +69% |
| CSRBT_Splay | 116,572,117,323 | 72,858 | catastrophic (see §2) |

Adaptive's behavior was *rational*: one early morph RB→AVL in phase 0 (its 4.9M phase-0 bill is
the RB warm-up plus the O(n) rebuild), then phases 1–7 track pure AVL exactly, strategy pinned
to AVL throughout. It lost because there was nothing to win, not because it thrashed.

## 1. There is no read-skew prize in this morph family

RB and AVL differ by ~1.5–2.5% per phase in opposite directions (AVL slightly better on the
skewed phases, RB on uniform). That spread is far below `MorphPolicy.defaults()`'s 20%
improvement gate — and *should* be: morphing between them costs an O(n) rebuild to capture a
~2% rate difference. A tracker that flipped every phase would lose worse. The adaptive arm
converging on one strategy and staying put is the control plane working correctly on a workload
with no headroom.

## 2. Splay adaptivity is structurally void for reads — by design

The one strategy whose *thesis* is read-skew adaptation cannot express it in CSRBT:
**ADR-004 R1 made public reads lock-free — they descend the engine directly and never consult
the strategy — so a read never splays.** A splay member only restructures on writes. Worse, the
census populated with ascending keys, and splay-on-insert drives sorted input into a left spine
(new max splayed to root each time); with reads unable to repair it, the spine persists forever
and the hot keys sit at the *deepest* end: ~50k cmp/read uniform, ~95k skewed. The number is an
artifact of sorted population, but the structural point survives any population order: a splay
tree in CSRBT cannot bubble hot read keys to the root, so "splay wins skewed reads" is not
available to the scorer, and the scorer was right never to pick it. Lock-free reads were traded
for read-adaptivity deliberately; this census is the first measurement of that trade's cost side.

## 3. What this means for the adaptivity thesis

Read-only phase shifts are the wrong axis: the strategies' read paths are all just balanced-tree
descents within a few percent of each other (splay excluded per §2). The axes where the morph
family *genuinely* diverges are **write mix** (rotation costs: RB vs AVL fix-up work differs
materially under churn; WRITE_HEAVY→RB clamp already encodes this belief — it is testable the
same way), **windowed churn** (`setMaxSize` eviction pressure), and **scale shifts** (the
ensemble's B+tree member vs in-heap trees). A follow-up census on a write-heavy phase shift
(same harness, `add`/`remove` mix flipping against read phases, morph rebuilds still billed)
is the cheap next experiment and tests a claim the codebase already acts on.
*(Built and run same day: `PhaseShiftWriteWorkTest` — verdict and findings in §5 below.)*

## 4. Dispositions

- The JMH wall-clock follow-up planned for a positive result is **not warranted** — the
  comparison census is adaptivity's best case (no morph wall-clock cost, no allocation noise)
  and it still lost.
- `PhaseShiftWorkTest` stays as a measurement harness but should not run in the default suite
  (~3 min, 116B splay comparisons): tag it or `@Disabled` with a pointer here, re-enable by hand
  when a variant is wanted. Run it alone with `.\gradlew :test --tests "*PhaseShiftWorkTest"` —
  note the leading `:test`, which keeps the filter off `:sbs-kernels-rust:test` (that task has
  no matching tests and fails the build with the bare `test` invocation).
- Census artifact: `build/phase-shift-census.txt` (regenerated each run).
- CSRBT capability audit note: this closes audit item #2
  (`CSRBT/docs/AUDIT-2026-07-14-capability-coverage.md`) — the "adaptivity earns its keep" claim
  is now measured; the honest answer on the read axis is recorded here.

---

## 5. Write-axis census (`PhaseShiftWriteWorkTest`, run 2026-07-14)

Seed 20260714, N=100k of a 200k space (shuffled population), 8 phases × 200k ops alternating
READ (uniform contains) and CHURN (50/50 add/remove).

| arm | comparisons | cmp/op | rotations |
|---|---|---|---|
| JDK_TreeMap | 26,270,250 | 16.42 | n/a |
| CSRBT_AVL | 32,991,353 | 20.62 | 186,232 |
| CSRBT_ADAPTIVE | 34,436,065 | 21.52 | 204,984 |
| CSRBT_Hybrid | 36,352,690 | 22.72 | 186,232 |
| CSRBT_RedBlack | 36,384,874 | 22.74 | 150,666 |
| CSRBT_Splay | 45,699,917 | 28.56 | **22,862,369** |
| JDK_SkipList | 57,726,649 | 36.08 | n/a |

Headline verdict: adaptive +31.1% vs JDK TreeMap. But the within-family story is different from
the read census, and two structural findings matter more than the verdict line.

**Adaptive finally beats its own default.** One early morph RB→AVL, pinned thereafter (again
rationally — see finding B), total 34.4M vs pinned-RB's 36.4M: a store born on the family default
and left to self-tune ends **5.4% cheaper than staying RB**, trailing omniscient-pure-AVL by only
4.4% (the phase-0 warm-up + rebuild). First positive evidence for the ADAPTIVE tier: it converges
to the right family member without being told. It still never re-morphs — with AVL dominating both
phase types there is nothing to track — so the *phase-tracking* claim remains unfunded on both
axes measured so far.

**Finding A — the double-descent write tax (CSRBT core).** TreeMap's churn phases cost the same
as its read phases (~3.28M); every CSRBT arm's churn phases cost ~1.77× that (RB 5.81M). Cause,
confirmed in source: `OrderedSet.add` runs `tree.contains(value)` (one full descent, and the
precheck may splay) and then `tree.add(value)` (a second full descent); `remove` is the same
shape. Every *successful* write pays two descents where TreeMap pays one. This—not tree shape—is
why the whole family loses the write axis to the JDK. Fix direction: engine-level single-descent
add/remove that report changed/not-changed (`RedBlackTree.add` returning boolean), keeping the
facade contract identical. Until then, CSRBT's competitive posture on write-heavy loads is
structurally capped, independent of strategy choice.

**Finding B — the WRITE_HEAVY→Red-Black belief is contradicted on this workload.** AVL beat RB on
the churn phases by 15% comparisons (4.96M vs 5.81M/phase) at the price of +24% rotations (186k
vs 151k total — absolutely tiny: ~35k extra rotations buys ~850k fewer comparisons *per phase*).
Unless a rotation is ~25× the cost of a comparison (it is not), AVL is the better write-heavy
family member here, and the live cost-model scorer agrees — the adaptive arm sat on AVL straight
through every churn phase. The static WRITE_HEAVY advice (WeightBalanced, clamped to RB inside
the morph family) encodes the opposite belief. Worth a JMH wall-clock check before touching the
advisor, since comparisons don't price cache effects — but the burden of proof has shifted.

**Splay, again, no.** The write path does let splay restructure (the precheck splays), and it
still finished last in the family with a 150× rotation explosion. Both axes now measured; splay
earns its keep in neither. Hybrid reads exactly like AVL (same balance) and writes like RB — and
so ties RB overall: the blend buys nothing here.

Census artifact: `build/phase-shift-write-census.txt`. The harness is left enabled (it runs in
seconds — no splay-spine pathology on this axis); disable alongside the read harness if the suite
ever feels it.

---

## 6. Post-fix re-run (same day): the tax is gone, and finding B dissolves

Finding A was fixed in CSRBT core within hours (`RedBlackTree.addIfAbsent`/`removeIfPresent`
replace the contains prechecks in `add`/`remove`/`evictOldest`; RB/Splay/Hybrid insert descents
now compare once per step; regression net `SingleDescentWriteTest`; full ring green). Same
census, same seed, re-run:

| arm | comparisons (was) | cmp/op | rotations |
|---|---|---|---|
| CSRBT_Hybrid | **26,239,741** (36.4M) | 16.40 | 186,232 |
| CSRBT_RedBlack | 26,270,250 (36.4M) | 16.42 | 150,666 |
| JDK_TreeMap | 26,270,250 (—) | 16.42 | n/a |
| CSRBT_AVL | 26,439,412 (33.0M) | 16.52 | 186,232 |
| CSRBT_ADAPTIVE | 27,947,218 (34.4M) | 17.47 | 204,984 |
| CSRBT_Splay | 36,837,888 (45.7M) | 23.02 | 19,368,085 |
| JDK_SkipList | 56,031,568 | 35.02 | n/a |

**CSRBT_RedBlack is now bit-identical to JDK TreeMap — total and every single phase.** Two CLRS
red-black trees, one descent per op, one comparison per step: perfect parity, and the strongest
possible confirmation the fix landed exactly. RB dropped 27.8%; Hybrid now edges out the JDK
outright while carrying morphability, order statistics, events, and the ensemble seams.

**Finding B is withdrawn — it was an artifact of finding A.** AVL's apparent 15% write advantage
existed only because RB's insert descent was paying two comparisons per step where AVL paid one.
With that fixed, RB beats AVL on the churn phases (3.281M vs 3.328M per phase) *and* holds 19%
fewer rotations. The WRITE_HEAVY→Red-Black clamp is vindicated. One residual note: the live cost
model still steered the adaptive arm to AVL (a defensible near-tie, −0.6% vs RB overall), which
suggests its coefficients were tuned against the old, double-comparing write path — worth a
recalibration pass whenever the scorer is next touched, not urgent.

Adaptive's entire 6.5% deficit is the phase-0 warm-up + one rebuild (1.71M ≈ exactly the gap to
best-static); it tracks its chosen strategy at parity thereafter. Two footnotes: JDK_SkipList is
the one nondeterministic arm (`ConcurrentSkipListMap` levels come from `ThreadLocalRandom`, so
its total wobbles run to run), and splay improved 19% from the shared descent fixes but remains
the family's wrong answer on both measured axes.
