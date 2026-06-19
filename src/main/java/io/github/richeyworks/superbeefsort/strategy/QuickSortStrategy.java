package io.github.richeyworks.superbeefsort.strategy;

import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.core.StrategyCapabilities;
import io.github.richeyworks.superbeefsort.core.StrategyId;

import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Three-way (Dutch-national-flag) quicksort with a randomized pivot and a sorting-network cutoff.
 * The three-way partition makes it resilient to inputs with many duplicate keys, and recursing on
 * the smaller side bounds stack depth to O(log n).
 *
 * <p>The pivot RNG is {@link ThreadLocalRandom} by default (fast, unpredictable — defeats adversarial
 * inputs in expectation). When the {@link SortContext} carries a seed (deterministic mode) it instead
 * uses a {@link SplittableRandom} seeded from it, so the exact pivot sequence — and thus the comparison
 * and move counts — are reproducible across runs.</p>
 */
public final class QuickSortStrategy<K> implements SortStrategy<K> {

    public static final StrategyId ID = StrategyId.of("quick.threeway");
    private static final int INSERTION_CUTOFF = SortingNetwork.MAX;

    @Override
    public void sort(SortBuffer<K> b, SortContext context) {
        RandomGenerator rng = context.randomSeed().isPresent()
                ? new SplittableRandom(context.randomSeed().getAsLong())
                : ThreadLocalRandom.current();
        quicksort(b, 0, b.size() - 1, rng);
    }

    private void quicksort(SortBuffer<K> b, int lo, int hi, RandomGenerator rng) {
        while (lo < hi) {
            if (hi - lo < INSERTION_CUTOFF) {
                SortingNetwork.sort(b, lo, hi - lo + 1); // small range: branchless comparator network
                return;
            }
            int p = lo + rng.nextInt(hi - lo + 1);
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
                quicksort(b, lo, lt - 1, rng);
                lo = gt + 1;
            } else {
                quicksort(b, gt + 1, hi, rng);
                hi = lt - 1;
            }
        }
    }

    @Override
    public StrategyCapabilities capabilities() {
        return StrategyCapabilities.builder().stable(false).inPlace(true)
                .auxMemory(StrategyCapabilities.AuxMemory.LOGARITHMIC).build();   // O(log n) recursion stack
    }

    @Override
    public StrategyId id() {
        return ID;
    }
}
