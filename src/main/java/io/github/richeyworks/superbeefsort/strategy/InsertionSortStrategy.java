package io.github.richeyworks.superbeefsort.strategy;

import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.core.StrategyCapabilities;
import io.github.richeyworks.superbeefsort.core.StrategyId;

/** Stable, adaptive insertion sort. The right pick for tiny or nearly-sorted inputs. */
public final class InsertionSortStrategy<K> implements SortStrategy<K> {

    public static final StrategyId ID = StrategyId.of("insertion");

    @Override
    public void sort(SortBuffer<K> b, SortContext context) {
        int n = b.size();
        for (int i = 1; i < n; i++) {
            K key = b.get(i);
            int j = i - 1;
            while (j >= 0 && b.compareToKey(j, key) > 0) {
                b.set(j + 1, b.get(j));
                b.recordMove();
                j--;
            }
            b.set(j + 1, key);
        }
    }

    @Override
    public StrategyCapabilities capabilities() {
        return StrategyCapabilities.builder().stable(true).inPlace(true).adaptive(true).build();
    }

    @Override
    public StrategyId id() {
        return ID;
    }
}
