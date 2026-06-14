package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.strategy.CountingSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.RadixSortStrategy;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Counting and radix (including negative keys, via the sign-flip) must match the reference sort. */
class NonComparisonSortPropertyTest {

    @Property(tries = 300)
    void countingAndRadixMatchReference(@ForAll("boundedInts") List<Integer> input) {
        List<Integer> expected = new ArrayList<>(input);
        expected.sort(Comparator.naturalOrder());
        KeyEncoder<Integer> encoder = KeyEncoder.ofInt(i -> i);

        List<SortStrategy<Integer>> strategies = List.of(new CountingSortStrategy<>(), new RadixSortStrategy<>());
        for (SortStrategy<Integer> strategy : strategies) {
            SortBuffer<Integer> buffer = SortBuffer.of(input, Comparator.<Integer>naturalOrder(), encoder);
            strategy.sort(buffer, SortContext.noop());
            assertEquals(expected, buffer.toList(), "strategy " + strategy.id());
        }
    }

    @Provide
    Arbitrary<List<Integer>> boundedInts() {
        return Arbitraries.integers().between(-1000, 1000).list().ofMaxSize(500);
    }
}
