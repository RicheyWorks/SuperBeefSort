package io.github.richeyworks.superbeefsort.strategy;

import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.core.StrategyCapabilities;
import io.github.richeyworks.superbeefsort.core.StrategyId;

/** In-place binary-heap sort. O(n log n) worst case, no extra memory, not stable. */
public final class HeapSortStrategy<K> implements SortStrategy<K> {

    public static final StrategyId ID = StrategyId.of("heap");

    @Override
    public void sort(SortBuffer<K> b, SortContext context) {
        int n = b.size();
        for (int i = n / 2 - 1; i >= 0; i--) {
            siftDown(b, i, n);
        }
        for (int end = n - 1; end > 0; end--) {
            b.swap(0, end);
            siftDown(b, 0, end);
        }
    }

    private void siftDown(SortBuffer<K> b, int root, int end) {
        while (true) {
            int child = 2 * root + 1;
            if (child >= end) {
                break;
            }
            if (child + 1 < end && b.compare(child + 1, child) > 0) {
                child++;
            }
            if (b.compare(child, root) > 0) {
                b.swap(root, child);
                root = child;
            } else {
                break;
            }
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
