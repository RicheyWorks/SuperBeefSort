package io.github.richeyworks.superbeefsort.strategy;

import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.core.StrategyCapabilities;
import io.github.richeyworks.superbeefsort.core.StrategyId;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Three-way (Dutch-national-flag) quicksort with a randomized pivot and an insertion-sort cutoff.
 * The three-way partition makes it resilient to inputs with many duplicate keys, and recursing on
 * the smaller side bounds stack depth to O(log n).
 */
public final class QuickSortStrategy<K> implements SortStrategy<K> {

    public static final StrategyId ID = StrategyId.of("quick.threeway");
    private static final int INSERTION_CUTOFF = 16;

    @Override
    public void sort(SortBuffer<K> b, SortContext context) {
        quicksort(b, 0, b.size() - 1);
    }

    private void quicksort(SortBuffer<K> b, int lo, int hi) {
        while (lo < hi) {
            if (hi - lo < INSERTION_CUTOFF) {
                insertion(b, lo, hi);
                return;
            }
            int p = lo + ThreadLocalRandom.current().nextInt(hi - lo + 1);
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
            // [lo, lt-1] < pivot ; [lt, gt] == pivot ; [gt+1, hi] > pivot
            if (lt - lo < hi - gt) {
                quicksort(b, lo, lt - 1);
                lo = gt + 1;
            } else {
                quicksort(b, gt + 1, hi);
                hi = lt - 1;
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
