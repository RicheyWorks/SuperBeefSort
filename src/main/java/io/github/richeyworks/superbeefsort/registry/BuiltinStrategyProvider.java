package io.github.richeyworks.superbeefsort.registry;

import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.strategy.CountingSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.HeapSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.InsertionSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.IntroSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.JdkSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.LearnedSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.MergeSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.QuickSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.RadixSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.SortingNetworkStrategy;

import java.util.List;

/** The built-in sort pack: Phase 0 comparison sorts plus the Phase 1 non-comparison sorts. */
public final class BuiltinStrategyProvider implements StrategyProvider {

    @Override
    public List<SortStrategy<?>> strategies() {
        return List.of(
                new InsertionSortStrategy<>(),
                new SortingNetworkStrategy<>(),
                new MergeSortStrategy<>(),
                new QuickSortStrategy<>(),
                new HeapSortStrategy<>(),
                new IntroSortStrategy<>(),
                new JdkSortStrategy<>(),
                new CountingSortStrategy<>(),
                new RadixSortStrategy<>(),
                new LearnedSortStrategy<>());
    }
}
