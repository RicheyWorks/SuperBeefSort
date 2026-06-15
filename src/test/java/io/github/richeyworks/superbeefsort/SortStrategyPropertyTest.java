package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.strategy.HeapSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.InsertionSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.IntroSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.JdkSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.MergeSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.QuickSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.SortingNetworkStrategy;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Property: every strategy must produce exactly the reference sort for any input (jqwik shrinks failures). */
class SortStrategyPropertyTest {

    private static List<SortStrategy<Integer>> strategies() {
        return List.of(
                new InsertionSortStrategy<>(),
                new SortingNetworkStrategy<>(),
                new MergeSortStrategy<>(),
                new QuickSortStrategy<>(),
                new HeapSortStrategy<>(),
                new IntroSortStrategy<>(),
                new JdkSortStrategy<>());
    }

    @Property(tries = 300)
    void everyStrategySortsLikeTheReference(@ForAll("intLists") List<Integer> input) {
        List<Integer> expected = new ArrayList<>(input);
        expected.sort(Comparator.naturalOrder());
        for (SortStrategy<Integer> strategy : strategies()) {
            SortBuffer<Integer> buffer = SortBuffer.of(input, Comparator.<Integer>naturalOrder());
            strategy.sort(buffer, SortContext.noop());
            assertEquals(expected, buffer.toList(), "strategy " + strategy.id());
        }
    }

    @Provide
    Arbitrary<List<Integer>> intLists() {
        return Arbitraries.integers().between(-1000, 1000).list().ofMaxSize(500);
    }
}
