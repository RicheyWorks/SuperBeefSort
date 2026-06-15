package io.github.richeyworks.superbeefsort.strategy;

import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.core.StrategyCapabilities;
import io.github.richeyworks.superbeefsort.core.StrategyId;

/**
 * Branchless small-sort kernel: sorts tiny inputs with a fixed Batcher comparator network
 * ({@link SortingNetwork}). Sorting networks are data-oblivious, so the work is fixed and
 * branch-predictable - the right base case for small inputs and the recursion leaves of the bigger
 * sorts. For {@code n > }{@link SortingNetwork#MAX} it falls back to adaptive insertion so the
 * strategy is correct for <em>any</em> input (the selector should only route small {@code n} here).
 *
 * <p>Not stable (a network may reorder equal keys); in place; comparison-based. The selector reaches
 * for this on tiny inputs where insertion would otherwise be used.</p>
 */
public final class SortingNetworkStrategy<K> implements SortStrategy<K> {

    public static final StrategyId ID = StrategyId.of("network.oddeven");

    @Override
    public void sort(SortBuffer<K> b, SortContext context) {
        int n = b.size();
        if (SortingNetwork.handles(n)) {
            SortingNetwork.sort(b, 0, n);
            return;
        }
        // Oversized input: stay correct with adaptive insertion rather than refusing to sort.
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
        return StrategyCapabilities.builder().stable(false).inPlace(true).adaptive(false).build();
    }

    @Override
    public StrategyId id() {
        return ID;
    }
}
