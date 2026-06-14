package io.github.richeyworks.superbeefsort.strategy;

import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.core.StrategyCapabilities;
import io.github.richeyworks.superbeefsort.core.StrategyId;

/**
 * Introsort: median-of-three quicksort with a recursion-depth guard that falls back to heapsort,
 * plus an insertion-sort cutoff. Guarantees O(n log n) worst case while keeping quicksort's speed —
 * the robust general-purpose default and the fallback for every other strategy.
 */
public final class IntroSortStrategy<K> implements SortStrategy<K> {

    public static final StrategyId ID = StrategyId.of("intro");
    private static final int CUTOFF = 16;

    @Override
    public void sort(SortBuffer<K> b, SortContext context) {
        int n = b.size();
        if (n < 2) {
            return;
        }
        int depthLimit = 2 * (31 - Integer.numberOfLeadingZeros(n)); // 2 * floor(log2 n)
        introsort(b, 0, n - 1, depthLimit);
    }

    private void introsort(SortBuffer<K> b, int lo, int hi, int depth) {
        while (hi - lo >= CUTOFF) {
            if (depth == 0) {
                heapRange(b, lo, hi);
                return;
            }
            depth--;
            int mid = lo + ((hi - lo) >>> 1);
            int p = medianOfThree(b, lo, mid, hi);
            b.swap(lo, p);
            K pivot = b.get(lo);

            int lt = lo, gt = hi, i = lo + 1;
            while (i <= gt) {
                int c = b.compareToKey(i, pivot);
                if (c < 0) {
                    b.swap(lt++, i++);
                } else if (c > 0) {
                    b.swap(i, gt--);
                } else {
                    i++;
                }
            }
            introsort(b, lo, lt - 1, depth);
            lo = gt + 1;
        }
        insertion(b, lo, hi);
    }

    /** Index of the median-valued of the three positions {@code x}, {@code y}, {@code z}. */
    private int medianOfThree(SortBuffer<K> b, int x, int y, int z) {
        if (b.compare(x, y) < 0) {
            if (b.compare(y, z) < 0) {
                return y;
            }
            return b.compare(x, z) < 0 ? z : x;
        } else {
            if (b.compare(z, y) < 0) {
                return y;
            }
            return b.compare(z, x) < 0 ? z : x;
        }
    }

    private void heapRange(SortBuffer<K> b, int lo, int hi) {
        int n = hi - lo + 1;
        for (int i = n / 2 - 1; i >= 0; i--) {
            siftDown(b, lo, i, n);
        }
        for (int end = n - 1; end > 0; end--) {
            b.swap(lo, lo + end);
            siftDown(b, lo, 0, end);
        }
    }

    private void siftDown(SortBuffer<K> b, int lo, int root, int end) {
        while (true) {
            int child = 2 * root + 1;
            if (child >= end) {
                break;
            }
            if (child + 1 < end && b.compare(lo + child + 1, lo + child) > 0) {
                child++;
            }
            if (b.compare(lo + child, lo + root) > 0) {
                b.swap(lo + root, lo + child);
                root = child;
            } else {
                break;
            }
        }
    }

    private void insertion(SortBuffer<K> b, int lo, int hi) {
        for (int i = lo + 1; i <= hi; i++) {
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

    @Override
    public StrategyCapabilities capabilities() {
        return StrategyCapabilities.builder().stable(false).inPlace(true).build();
    }

    @Override
    public StrategyId id() {
        return ID;
    }
}
