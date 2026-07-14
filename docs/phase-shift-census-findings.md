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
