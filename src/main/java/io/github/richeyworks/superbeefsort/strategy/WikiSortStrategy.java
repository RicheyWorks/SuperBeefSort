package io.github.richeyworks.superbeefsort.strategy;

import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.core.StrategyCapabilities;
import io.github.richeyworks.superbeefsort.core.StrategyId;

/**
 * Stable, O(1)-auxiliary <em>block merge</em> sort — the O(n&nbsp;log&nbsp;n) successor to
 * {@link InPlaceMergeSortStrategy} (ID {@code merge.inplace}, O(n&nbsp;log&sup2;&nbsp;n)). Where the
 * rotation-based in-place merge pays O(n&nbsp;log&nbsp;n) element moves <em>per merge level</em>, this
 * merges each level in linear moves by carving a small <em>internal buffer</em> out of the data and
 * using it as swap scratch — Kronrod's classic technique, popularised by BonzaiThePenguin's "WikiSort".
 *
 * <p>Structure (bottom-up and iterative, so the call stack stays O(1) regardless of n):</p>
 * <ol>
 *   <li>Insertion-sort initial runs of length {@link #CUTOFF}.</li>
 *   <li>Bottom-up doubling: merge adjacent runs of width 16, 32, 64, &hellip;</li>
 *   <li>Each merge of sorted runs A and B either:
 *     <ul>
 *       <li>takes the <b>block-merge fast path</b> (linear moves) when the combined region is large
 *           enough and entirely distinct, or</li>
 *       <li>falls back to a stable <b>rotation merge</b> — correct and stable for <em>any</em> input,
 *           including duplicate-heavy and tiny runs.</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>The block-merge path pulls the {@code bufLen} largest elements aside as an internal buffer (they
 * come to rest at the far right, already in their final home), aligns the A/B seam to the block grid,
 * selection-sorts the √n-sized blocks by head value, then repairs block seams with buffer-backed local
 * merges. A final insertion sort tidies the buffer in place — no re-merge into the body is needed,
 * because the buffer holds the largest values and is already where it belongs.</p>
 *
 * <p><b>Stability.</b> The fast path runs only when {@link #fullyDistinct} confirms the merged region
 * has no repeated value, so there are never equal keys for block selection to mis-order; every input
 * with duplicates is routed to the stable rotation merge instead. Local merges still break ties toward
 * the left (A) run. The output matches a stable reference sort on every input. Empirically the move
 * count grows like O(n&nbsp;log&nbsp;n) (versus {@code merge.inplace}'s O(n&nbsp;log&sup2;&nbsp;n)),
 * at the cost of a larger comparison constant — the usual block-merge trade.</p>
 */
public final class WikiSortStrategy<K> implements SortStrategy<K> {

    public static final StrategyId ID = StrategyId.of("merge.wiki");
    private static final int CUTOFF = 16;

    @Override
    public void sort(SortBuffer<K> b, SortContext context) {
        int n = b.size();
        if (n <= 1) {
            return;
        }
        // 1. insertion-sort initial runs
        for (int lo = 0; lo < n; lo += CUTOFF) {
            insertion(b, lo, Math.min(lo + CUTOFF, n));
        }
        // 2. bottom-up doubling merges (long arithmetic keeps width<<1 from overflowing int)
        for (long width = CUTOFF; width < n; width <<= 1) {
            for (long lo = 0; lo < n; lo += (width << 1)) {
                int l = (int) lo;
                int mid = (int) Math.min(lo + width, n);
                int hi = (int) Math.min(lo + (width << 1), n);
                if (mid < hi) {
                    merge(b, l, mid, hi);
                }
            }
        }
    }

    // ---- merge dispatch ----

    /** Stable merge of sorted {@code [lo,mid)} and {@code [mid,hi)} in O(1) extra space. */
    private void merge(SortBuffer<K> b, int lo, int mid, int hi) {
        if (lo >= mid || mid >= hi) {
            return;
        }
        if (b.compare(mid - 1, mid) <= 0) {
            return; // already ordered across the seam (and stable)
        }
        int total = hi - lo;
        int blockSize = (int) Math.sqrt(total);
        // The block-merge fast path needs at least two full blocks and an all-distinct region (so block
        // selection has no ties to mis-order). Everything else uses the always-correct rotation merge.
        if (blockSize < 2 || total < 2 * blockSize || !fullyDistinct(b, lo, mid, hi)) {
            rotationMerge(b, lo, mid, hi);
            return;
        }
        blockMerge(b, lo, mid, hi, blockSize);
    }

    // ---- WikiSort block merge (region is all-distinct -> block heads are distinct) ----

    private void blockMerge(SortBuffer<K> b, int lo, int mid, int hi, int blockSize) {
        int total = hi - lo;
        // Size the buffer so the merge region tiles into whole blocks: bufLen in [blockSize, 2*blockSize).
        int bufLen = blockSize + (total % blockSize);

        // 1. Pull the bufLen largest elements to the far right [hi-bufLen, hi). They are split between the
        //    tails of A=[lo,mid) and B=[mid,hi); count how many come from each.
        int fromA = 0;
        int fromB = 0;
        int i = mid - 1;
        int j = hi - 1;
        while (fromA + fromB < bufLen) {
            if (j < mid || (i >= lo && b.compare(i, j) >= 0)) {
                i--;
                fromA++;
            } else {
                j--;
                fromB++;
            }
        }
        // B's chosen tail [hi-fromB, hi) is already at the right; bring A's tail [mid-fromA, mid) up next
        // to it by rotating the B-middle [mid, hi-fromB) ahead of the A-tail.
        rotate(b, mid - fromA, mid, hi - fromB);
        int bufLo = hi - bufLen;           // buffer = [bufLo, hi): the bufLen largest values
        int mergeEnd = bufLo;
        int aEnd = mid - fromA;            // after the rotate, M=[lo,mergeEnd) is A''=[lo,aEnd) ++ B''=[aEnd,mergeEnd)

        // 2. Align the A''/B'' seam to the block grid: the A'' tail [aFull,aEnd) is the only part off a
        //    block boundary, so merge it into B''. Because M is a whole number of blocks and A'' head is
        //    now k whole blocks, B'' becomes a whole number of blocks too — every block below is full and
        //    individually sorted.
        int fracA = (aEnd - lo) % blockSize;
        int aFull = aEnd - fracA;
        if (fracA > 0) {
            localMerge(b, aFull, aEnd, mergeEnd, bufLo);
        }
        int blocks = (mergeEnd - lo) / blockSize;   // exact

        // 3. Selection-sort the blocks of M by head value (distinct values -> no ties). O(1) space.
        for (int s = 0; s < blocks - 1; s++) {
            int minBlock = s;
            int minHead = lo + s * blockSize;
            for (int t = s + 1; t < blocks; t++) {
                int head = lo + t * blockSize;
                if (b.compare(head, minHead) < 0) {
                    minBlock = t;
                    minHead = head;
                }
            }
            if (minBlock != s) {
                blockSwap(b, lo + s * blockSize, lo + minBlock * blockSize, blockSize);
            }
        }

        // 4. Repair block seams left-to-right. After sorting by head, merging each block with the running
        //    "carry" (the right half left by the previous local merge) completes the sort; the buffer
        //    backs each O(blockSize) local merge.
        for (int s = 1; s < blocks; s++) {
            int l = lo + (s - 1) * blockSize;
            int m = lo + s * blockSize;
            int r = lo + (s + 1) * blockSize;
            localMerge(b, l, m, r, bufLo);
        }

        // 5. Tidy the internal buffer. It holds the largest values and already sits at the far right, so
        //    an in-place insertion sort finishes the job — no re-merge into the body required.
        insertion(b, bufLo, hi);
    }

    /**
     * Stable merge of sorted {@code [l,m)} and {@code [m,r)} using {@code [bufLo, bufLo+(m-l))} as swap
     * scratch. Requires {@code (m-l) <=} the buffer length. Ties resolve toward the left run.
     */
    private void localMerge(SortBuffer<K> b, int l, int m, int r, int bufLo) {
        int leftLen = m - l;
        if (leftLen == 0 || m == r) {
            return;
        }
        if (b.compare(m - 1, m) <= 0) {
            return; // already ordered
        }
        // Park the left run in the buffer (swap), then merge the parked copy with the right run back into
        // [l,r). Displaced buffer "garbage" stays confined to the buffer region by the end.
        blockSwap(b, l, bufLo, leftLen);
        int i = bufLo;
        int iEnd = bufLo + leftLen;
        int jj = m;
        int w = l;
        while (i < iEnd && jj < r) {
            if (b.compare(i, jj) <= 0) {     // left <= right -> take left (stable: A before equal B)
                b.swap(w++, i++);
            } else {
                b.swap(w++, jj++);
            }
        }
        while (i < iEnd) {                    // drain remaining parked-left elements
            b.swap(w++, i++);
        }
        // any remaining right elements are already in their final positions
    }

    // ---- stable rotation merge (fallback) — O(1) space, correct for ALL inputs ----

    /** Stable rotation-based merge of sorted {@code [lo,mid)} and {@code [mid,hi)}; O(1) space. */
    private void rotationMerge(SortBuffer<K> b, int lo, int mid, int hi) {
        int len1 = mid - lo;
        int len2 = hi - mid;
        if (len1 == 0 || len2 == 0) {
            return;
        }
        if (len1 + len2 == 2) {
            if (b.compare(mid, lo) < 0) {
                b.swap(lo, mid);
            }
            return;
        }
        int q1;
        int q2;
        if (len1 >= len2) {
            q1 = lo + len1 / 2;
            K pivot = b.get(q1);
            q2 = lowerBound(b, mid, hi, pivot);   // lower_bound when left is larger -> stable
        } else {
            q2 = mid + len2 / 2;
            K pivot = b.get(q2);
            q1 = upperBound(b, lo, mid, pivot);   // upper_bound when right is larger -> stable
        }
        rotate(b, q1, mid, q2);
        int newMid = q1 + (q2 - mid);
        rotationMerge(b, lo, q1, newMid);
        rotationMerge(b, newMid, q2, hi);
    }

    // ---- primitives ----

    private void insertion(SortBuffer<K> b, int lo, int hi) {
        for (int i = lo + 1; i < hi; i++) {
            K key = b.get(i);
            int j = i - 1;
            while (j >= lo && b.compareToKey(j, key) > 0) {
                b.set(j + 1, b.get(j));
                b.recordMove();
                j--;
            }
            b.set(j + 1, key);
        }
    }

    /**
     * True iff merging the sorted runs {@code [lo,mid)} and {@code [mid,hi)} yields a strictly increasing
     * sequence — i.e. every value in the whole region is distinct (no within-run or cross-run duplicate).
     * The block-merge path is only stable when this holds.
     */
    private boolean fullyDistinct(SortBuffer<K> b, int lo, int mid, int hi) {
        int i = lo;
        int j = mid;
        int prev = -1;
        while (i < mid && j < hi) {
            int c = b.compare(i, j);
            if (c == 0) {
                return false;                 // a value shared by both runs
            }
            int take = (c < 0) ? i++ : j++;
            if (prev >= 0 && b.compare(prev, take) >= 0) {
                return false;                 // merged order not strictly increasing
            }
            prev = take;
        }
        while (i < mid) {
            if (prev >= 0 && b.compare(prev, i) >= 0) {
                return false;
            }
            prev = i++;
        }
        while (j < hi) {
            if (prev >= 0 && b.compare(prev, j) >= 0) {
                return false;
            }
            prev = j++;
        }
        return true;
    }

    /** Swap the two equal-length, non-overlapping ranges {@code [x,x+len)} and {@code [y,y+len)}. */
    private void blockSwap(SortBuffer<K> b, int x, int y, int len) {
        for (int k = 0; k < len; k++) {
            b.swap(x + k, y + k);
        }
    }

    /** First index in {@code [lo,hi)} whose element is {@code >= key}. */
    private int lowerBound(SortBuffer<K> b, int lo, int hi, K key) {
        while (lo < hi) {
            int m = (lo + hi) >>> 1;
            if (b.compareToKey(m, key) < 0) {
                lo = m + 1;
            } else {
                hi = m;
            }
        }
        return lo;
    }

    /** First index in {@code [lo,hi)} whose element is {@code > key}. */
    private int upperBound(SortBuffer<K> b, int lo, int hi, K key) {
        while (lo < hi) {
            int m = (lo + hi) >>> 1;
            if (b.compareToKey(m, key) <= 0) {
                lo = m + 1;
            } else {
                hi = m;
            }
        }
        return lo;
    }

    /** Rotate {@code [lo,hi)} so the block {@code [mid,hi)} precedes {@code [lo,mid)} — three reversals. */
    private void rotate(SortBuffer<K> b, int lo, int mid, int hi) {
        reverse(b, lo, mid);
        reverse(b, mid, hi);
        reverse(b, lo, hi);
    }

    private void reverse(SortBuffer<K> b, int from, int to) {
        int i = from;
        int j = to - 1;
        while (i < j) {
            b.swap(i, j);
            i++;
            j--;
        }
    }

    @Override
    public StrategyCapabilities capabilities() {
        return StrategyCapabilities.builder().stable(true).inPlace(true).comparisonBased(true).build();
    }

    @Override
    public StrategyId id() {
        return ID;
    }
}
