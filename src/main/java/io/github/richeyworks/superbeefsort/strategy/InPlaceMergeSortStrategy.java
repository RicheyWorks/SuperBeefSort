package io.github.richeyworks.superbeefsort.strategy;

import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.core.StrategyCapabilities;
import io.github.richeyworks.superbeefsort.core.StrategyId;

/**
 * Stable merge sort that merges <em>in place</em> — O(1) auxiliary memory, unlike {@link MergeSortStrategy}'s
 * O(n) scratch buffer. The merge is the classic rotation-based symmetric algorithm (as in the STL's
 * {@code __merge_without_buffer}): to merge two adjacent sorted runs it bisects the larger run, binary-searches
 * the split point in the other run, rotates the two middle blocks past each other with a three-reversal
 * rotation, and recurses on the two halves. Choosing {@code lower_bound} when the left run is the larger and
 * {@code upper_bound} otherwise keeps equal elements in input order, so the sort is stable.
 *
 * <p>The trade is time for space: rotations cost O(n log n) per top-level merge, so the whole sort is
 * O(n log&sup2; n) — the right pick when stability is required but an O(n) buffer is not affordable. Small
 * ranges fall back to a stable insertion sort, and an already-ordered seam is skipped (adaptive on
 * nearly-sorted input). Recursion is logarithmic in depth on both the sort and the merge, so the call stack
 * stays shallow.</p>
 */
public final class InPlaceMergeSortStrategy<K> implements SortStrategy<K> {

    public static final StrategyId ID = StrategyId.of("merge.inplace");
    private static final int CUTOFF = 16;

    @Override
    public void sort(SortBuffer<K> b, SortContext context) {
        sort(b, 0, b.size());
    }

    private void sort(SortBuffer<K> b, int lo, int hi) {
        if (hi - lo <= 1) {
            return;
        }
        if (hi - lo <= CUTOFF) {
            insertion(b, lo, hi);
            return;
        }
        int mid = (lo + hi) >>> 1;
        sort(b, lo, mid);
        sort(b, mid, hi);
        if (b.compare(mid - 1, mid) <= 0) {
            return; // runs already in order across the seam: nothing to merge (and stable)
        }
        merge(b, lo, mid, hi);
    }

    /** Stable rotation-based merge of sorted {@code [lo,mid)} and {@code [mid,hi)} using O(1) extra space. */
    private void merge(SortBuffer<K> b, int lo, int mid, int hi) {
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
            q1 = lo + len1 / 2;                       // bisect the left run
            K pivot = b.get(q1);
            q2 = lowerBound(b, mid, hi, pivot);       // first right element >= pivot (lower_bound -> stable)
        } else {
            q2 = mid + len2 / 2;                       // bisect the right run
            K pivot = b.get(q2);
            q1 = upperBound(b, lo, mid, pivot);       // first left element > pivot (upper_bound -> stable)
        }
        rotate(b, q1, mid, q2);                        // bring [mid,q2) ahead of [q1,mid)
        int newMid = q1 + (q2 - mid);
        merge(b, lo, q1, newMid);
        merge(b, newMid, q2, hi);
    }

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

    /** Rotate {@code [lo,hi)} so that {@code [mid,hi)} precedes {@code [lo,mid)} — three reversals, O(1) space. */
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
