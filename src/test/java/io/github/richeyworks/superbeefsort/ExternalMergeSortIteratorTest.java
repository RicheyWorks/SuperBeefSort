package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.superbeefsort.external.ExternalSortResult;
import io.github.richeyworks.superbeefsort.external.SpillSerializer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The streaming (Iterator) external-sort path (Phase 3.2). Its contract is simple and strict: it
 * must produce byte-for-byte the same output as the list path on the same data — the list path
 * ({@link BeefSort.ExternalSortBuilder#toList()}) is the oracle — including at run boundaries.
 */
class ExternalMergeSortIteratorTest {

    private static List<Integer> viaList(List<Integer> in, int runSize, int fanIn) throws IOException {
        return BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(in)
                .external(SpillSerializer.forIntegers())
                .runSize(runSize).fanIn(fanIn)
                .toList();
    }

    private static List<Integer> viaIterator(List<Integer> in, int runSize, int fanIn) throws IOException {
        return BeefSort.with(Comparator.<Integer>naturalOrder())
                .external(SpillSerializer.forIntegers())     // no source(list) needed for the iterator path
                .runSize(runSize).fanIn(fanIn)
                .toList(in.iterator());
    }

    private static List<Integer> random(int n, int range, long seed) {
        Random r = new Random(seed);
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) a.add(r.nextInt(range));
        return a;
    }

    @Test
    void iteratorPathEqualsListPathAcrossShapes() throws IOException {
        int[] sizes = {0, 1, 9, 10, 11, 100, 250};
        for (int n : sizes) {
            List<Integer> in = random(n, 50, n * 31L + 1);
            List<Integer> expected = viaList(in, 10, 4);
            List<Integer> actual = viaIterator(in, 10, 4);
            assertEquals(expected, actual, "iterator vs list at n=" + n);
        }
    }

    @Test
    void runBoundaryStraddlingMatchesReference() throws IOException {
        // runSize=10 over 25 elements → three runs (10,10,5): the straddle case.
        List<Integer> in = random(25, 100, 4242L);
        List<Integer> reference = new ArrayList<>(in);
        reference.sort(Comparator.naturalOrder());
        assertEquals(reference, viaIterator(in, 10, 16));
    }

    @Test
    void multiPassIteratorMatchesReference() throws IOException {
        // 500 elements, runSize=7 → 72 runs, fanIn=4 → multiple passes, all streamed.
        List<Integer> in = random(500, 1_000, 99L);
        List<Integer> reference = new ArrayList<>(in);
        reference.sort(Comparator.naturalOrder());
        assertEquals(reference, viaIterator(in, 7, 4));
    }

    @Test
    void feedFromStreamsIntoOrderedSet() throws IOException {
        List<Integer> in = random(300, 80, 5L);
        List<Integer> distinctSorted = new ArrayList<>(new java.util.TreeSet<>(in));
        OrderedSet<Integer> set = OrderedSet.withNaturalOrder(new RedBlackStrategy<Integer>());
        ExternalSortResult res = BeefSort.with(Comparator.<Integer>naturalOrder())
                .external(SpillSerializer.forIntegers())
                .runSize(40).fanIn(8)
                .feedFrom(in.iterator(), set);
        assertEquals(300, res.elements(), "iterator element count is tallied while draining");
        assertEquals(distinctSorted.size(), set.size());
        assertEquals(distinctSorted.get(0), set.minimum());
        assertEquals(distinctSorted.get(distinctSorted.size() - 1), set.maximum());
        assertTrue(res.elapsedNanos() > 0);
    }

    @Test
    void emptyIteratorProducesEmptyList() throws IOException {
        assertEquals(List.of(), viaIterator(List.of(), 100, 16));
    }
}
