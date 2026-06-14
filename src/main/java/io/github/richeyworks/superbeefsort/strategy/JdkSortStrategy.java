package io.github.richeyworks.superbeefsort.strategy;

import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.core.StrategyCapabilities;
import io.github.richeyworks.superbeefsort.core.StrategyId;

import java.util.ArrayList;
import java.util.List;

/**
 * Baseline that delegates to the JDK's adaptive, stable sort (TimSort). Useful as a correctness
 * oracle and a sensible fallback; its internal comparisons are not metered through the buffer.
 */
public final class JdkSortStrategy<K> implements SortStrategy<K> {

    public static final StrategyId ID = StrategyId.of("jdk.timsort");

    @Override
    public void sort(SortBuffer<K> b, SortContext context) {
        int n = b.size();
        List<K> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(b.get(i));
        }
        list.sort(b.comparator());
        for (int i = 0; i < n; i++) {
            b.set(i, list.get(i));
        }
    }

    @Override
    public StrategyCapabilities capabilities() {
        return StrategyCapabilities.builder().stable(true).inPlace(false).adaptive(true).build();
    }

    @Override
    public StrategyId id() {
        return ID;
    }
}
