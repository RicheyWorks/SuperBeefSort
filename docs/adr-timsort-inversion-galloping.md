# ADR: Fold the global inversion signal into the cost model's TimSort estimate

**Status:** Accepted
**Date:** 2026-06-20
**Deciders:** Richmond (with Claude)
**Related:** `select/CostModelStrategySelector.java`, `profile/DataProfile.java` (`inversions` / `inversionRatio()`), `PROGRESS.md`, `docs/HANDOFF.md` (the "fold the inversion estimate into the TimSort run-count model" open item)

---

## Context

`CostModelStrategySelector` estimates TimSort's cost from the number of natural runs, inferred from the
**adjacency** `sortednessRatio` (the fraction of adjacent pairs in order):

```
runs        = 1 + (1 - sortednessRatio)·(n - 1)
timsortCost = TIMSORT_OVERHEAD · n · (log2(runs) + 1)
```

`DataProfile` also carries a **global** disorder signal, `inversions` / `inversionRatio()` (Kendall-tau
distance: 0 = sorted, ~0.5 = random, 1 = reversed), but it was used only to route to *insertion* sort
(exact-gated). The HANDOFF listed "fold the inversion estimate into the TimSort run-count model" as an open
item. This ADR records how we did it — and, importantly, how we did **not**.

## The tempting-but-wrong approach

The naive reading is "replace (or blend) the adjacency run-count with an inversion-derived one." This is
**wrong for TimSort**, because adjacency is the *correct* driver of TimSort's cost and inversions is not:

| Input | adjacency run-count | inversionRatio | TimSort reality | Correct signal |
|---|---|---|---|---|
| rotation `[k+1..n,1..k]` | ~2 runs (cheap) | high | cheap (1 merge) | **adjacency** |
| far-apart swaps | few runs (cheap) | high | cheap | **adjacency** |
| sawtooth `[2,1,4,3,…]` | ~n/2 runs (costly) | ~0 (low) | costly (~n log n) | **adjacency** |

In every case where the two signals disagree, **adjacency is right and inversions is wrong** for the run
cost. A model that let inversions raise the run-count would over-charge rotations/far-apart swaps; one that
let it lower the run-count would under-charge sawtooth. So inversions must not touch the run *count*.

## Decision

Keep the adjacency run-count, and fold the inversion signal in as a **galloping discount on the merge
levels**, gated on *both* signals agreeing the data is ordered:

```
mergeLevels = log2(runs)
if inversions were measured:                                       // else: original adjacency-only estimate
    globalOrder = min(sortednessRatio, 1 - inversionRatio)         // in [0,1]
    mergeLevels *= (1 - GALLOP_DISCOUNT · globalOrder)             // GALLOP_DISCOUNT = 0.4
timsortCost = TIMSORT_OVERHEAD · n · (mergeLevels + 1)
```

Rationale: TimSort's merges **gallop** — collapsing toward O(n) — only when the data is ordered *globally*,
not merely locally. The `min(...)` grants galloping credit only when adjacency says "few descents" **and**
the inversion count says "little global displacement." This is exactly the signal adjacency alone cannot
provide: it suppresses the discount for inputs that look locally tidy but are globally scrambled (high
`inversionRatio`), while never over-charging genuinely-cheap rotations (whose *adjacency* is already high but
whose discount is harmless, since their run-count is tiny anyway).

**Safety.** `min(...)` caps the discount at the local `sortednessRatio`, so a noisy or sampled inversion
estimate can only *reduce* the discount, never inflate it — and TimSort is O(n log n) regardless, so there is
no O(n²) cliff (unlike the insertion route, which is exact-gated for that reason). When no inversion count was
measured the discount is **skipped entirely**, so the estimate is **byte-identical** to the previous
adjacency-only one. No exact-gating is needed — a sampled count is safe to use.

## Consequences

- Two inputs with identical adjacency sortedness can now score differently: a globally-ordered one (low
  inversions) earns the galloping discount and can be chosen as TimSort where the adjacency-only model fell to
  introsort; a locally-tidy-but-globally-disordered one (high inversions) is not over-credited.
- `GALLOP_DISCOUNT = 0.4` is calibrated so the random → introsort boundary holds (at `sortednessRatio = 0.5`
  the discounted TimSort estimate stays above introsort). Verified numerically.
- SMART-only and cost-model-only: `RuleBasedStrategySelector` is unchanged, and the soft aux penalty / hard
  budget compose with this unchanged.
- All prior `CostModelSelectorTest` cases are preserved; two new cases pin the flip (same 0.70 adjacency, 10%
  vs 70% of max inversions → TimSort vs introsort).

## Alternatives rejected

- **Inversion-derived run count (replace/blend):** over/under-charges rotations and sawtooth (table above).
- **`min(log2(runs), log2(inv/n+1))` as a tighter upper bound:** `log2(inv/n+1)` is not a valid TimSort
  upper bound (it under-estimates sawtooth), so the min under-charges.
- **Exact-gating the signal (like insertion):** unnecessary here — the `min` structure is already safe against
  a bad sample, and gating would discard the useful sampled signal on large SHALLOW inputs.
