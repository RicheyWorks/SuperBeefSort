# ADR: Duplicate-tolerant stable block merge for `merge.wiki`

Status: **Superseded by implementation** — the proposed per-merge `blockMergeDup` was *not* shipped as written; `WikiSortStrategy` was instead replaced with a faithful no-cache WikiSort port that handles duplicates natively. See the **Implementation note** at the end for why.
Date: 2026-06-19
Component: `strategy/WikiSortStrategy.java`

## Context

`merge.wiki` is the O(n log n) stable in-place block merge. Its block-merge fast path runs **only when the merged region is all-distinct** — that is the gate (`crossRunEqual` / `fullyDistinct`) that guarantees stability today. Any region containing a duplicate routes the *whole* merge to the O(n log² n) rotation fallback.

The `RuleBasedStrategySelector` routes only **large, mostly-distinct, `STABLE`** inputs to WikiSort, so the gap that matters in practice is **near-distinct** data: a handful of duplicate keys in an otherwise-distinct multi-million-element array. Today every merge whose region spans a duplicate loses the O(n log n)-move benefit — and the final top-level merge always spans the whole array, so a single duplicate pulls the largest merge onto the O(n log² n) path. The threaded `trustDistinct` gate also permanently drops to the exact `fullyDistinct` check after the first duplicate.

**Goal:** a stable block-merge path that tolerates cross-run duplicates while preserving O(n log n) moves **and strict O(1) auxiliary space** (WikiSort's defining property — the reason it is chosen over plain merge despite a higher comparison constant).

## Why duplicates break the current fast path

1. **Block selection.** The path selection-sorts √n-sized blocks by head value. Equal heads are ties a plain selection sort can reorder, violating stability.
2. **Seam merges.** Buffer-backed `localMerge` breaks ties "toward the left run," correct only when the left side is the A-origin. After blocks roll past each other, the left side of a seam may be B-origin, so the tie-break must follow **A/B origin**, which raw position no longer reveals.
3. **Redistribution shortcut.** "Merge buffer = largest values, insertion-sort at the end, no re-merge" is stable only if the buffer values are strictly greater than the body. With duplicates equal to the buffer minimum, stability depends on *which* occurrences were buffered.

## Decision

Add a second block-merge routine, `blockMergeDup`, for non-distinct large regions. Keep the all-distinct `blockMerge` fast path (primary route, lowest constant) and the rotation merge (final fallback). `blockMergeDup` is the standard Kim–Kutzner / GrailSort construction — two redistribution-free internal buffers plus movement-imitation tagging — adapted to `SortBuffer` and the existing `localMerge`/`rotate` primitives:

1. **Merge buffer** = the `bufLen` (≈√n) largest values, pulled to the back `[hi-bufLen, hi)`. Tie-break among equal-largest toward **later input**, so the buffer — left at the far right — is already in stable final position (insertion-sort only, no re-merge).
2. **Tag (imitation) buffer** = `numAblocks` **distinct smallest** values, pulled to the front `[lo, lo+t)`. Tie-break toward **earlier input** so it is redistribution-free at the front. If the region has too few distinct values to form it (low cardinality), **abort → rotation merge**.
3. **Block selection.** The middle `W = A' ++ B'` (each still sorted) tiles into √n blocks. Selection-sort blocks by `(head, origin)`; mirror every block swap on the tag buffer, so the tag permutation records each block's A/B origin and ties between equal heads break **A-before-B**.
4. **Seam repair.** Buffer-backed `localMerge` per adjacent block boundary, with the tie-break direction read from the **tags** (A side wins ties) — each linear-move local merge becomes stable under duplicates.
5. **Redistribution.** Insertion-sort the front tag buffer and the back merge buffer in place; both already occupy their final regions, so no re-merge into the body. O(√n·√n) = O(n) per merge.

**Complexity:** O(n log n) moves and comparisons retained; O(1) auxiliary (a few indices — both buffers are carved from the data). Stability holds by construction (origin-tagged ties). Low-cardinality regions use the existing stable rotation merge.

## Consequences

- **Pro:** near-distinct and general duplicate-bearing inputs keep O(n log n) moves instead of degrading to O(n log² n); WikiSort's `STABLE`-route benefit extends past the all-distinct case.
- **Con:** new code in the most subtle part of the WikiSort family, with delicate stability edge cases. Mitigated by gating every change on the differential + stability harness (see below) — nothing ships that is not provably stable.
- **Con:** higher comparison constant than the all-distinct fast path (extra buffer pulls + tag bookkeeping), so the all-distinct path stays primary and `blockMergeDup` engages only when duplicates are present.
- **Con:** genuinely low-cardinality inputs still use rotation merge — acceptable, since those are not routed to WikiSort and the tag buffer cannot be formed.

## Alternatives considered

- **O(√n) origin bitset** instead of a tag buffer: far simpler, only hundreds of bytes even at millions of elements — but breaks the strict O(1)-aux contract the selectors and docs rely on. Rejected to preserve WikiSort's defining property.
- **Positional A-block roll** (WikiSort's original min-A-block pointer dance, no tag buffer): also O(1) and a slightly lower constant, but harder to reason about and verify than explicit tags. Deferred as a possible constant-factor follow-up once the tagged version is proven.
- **Splitting the region at duplicate values:** cross-run interleaving prevents a clean decomposition. Rejected.

## Verification plan

- **Standalone harness** (no CSRBT needed): output `==` JDK stable sort across pathological shapes; distinct / near-distinct / duplicate-heavy randomized fuzz (thousands of cases); explicit stability check on `(key, seq)` records with dense ties.
- **Real `WikiSortTest` + `DifferentialTest`** (incl. the jqwik duplicate-heavy property) via JUnit.
- **`moveCurve` on duplicate-bearing inputs:** moves/(n·log₂n) should flatten toward the all-distinct curve instead of rising with n.

## Implementation note (correction)

The design above was **not implemented as written.** Working through a verified Python prototype against the reference (BonzaiThePenguin's WikiSort) surfaced two problems with the per-merge framing:

1. **Buffers are per-level, not per-merge.** WikiSort extracts its two √n internal buffers **once per merge level** (shared across every A/B merge in that level) and redistributes them at the end of the level — they are *unique-value* buffers pulled from the data, not the "largest-to-back" buffer the existing distinct fast path pulled per-merge. Bolting a per-merge `blockMergeDup` onto the existing structure therefore fights the proven algorithm's buffer model.

2. **The symmetric "block-sort + origin-tagged seams" is not stable.** Sorting all blocks by `(head, origin)` and then repairing seams left-to-right does not yield a stable result, because the running carry mixes A- and B-origin elements and no single per-block tie-break rule is correct. WikiSort's stability comes from an **asymmetric A-block roll**: B-blocks stay put, A-blocks roll through, and each seam merge is always *one A block vs B values*, so ties always resolve toward A. Block **selection** uses the unique-value tag buffer to break head ties by original order.

**Decision taken:** replace `WikiSortStrategy`'s internals with a faithful no-cache port of WikiSort (Kim & Kutzner block merge) — which handles distinct and duplicate input uniformly — rather than maintain two parallel buffer mechanisms. The all-distinct-only fast path and the threaded-`trustDistinct` comparison optimization it carried are superseded. `StrategyId`, `StrategyCapabilities` (stable + in-place + O(1) aux), and SPI registration are unchanged, so selectors and the engine are untouched. Verified by fuzzing the Python prototype to stability (~7k cases) and re-fuzzing a Python mirror transcribed back from the Java port (~7.4k cases) to catch translation drift; gated on `WikiSortTest` + `DifferentialTest` in the build.
