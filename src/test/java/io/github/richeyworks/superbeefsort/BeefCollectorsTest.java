package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.strategy.AVLStrategy;
import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.csrbt.AccessPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stream integration ({@link BeefCollectors}): collecting a stream runs the engine once in the finisher,
 * yielding a sorted {@link List} or a born-optimal CSRBT {@link OrderedSet}. The collectors must be correct
 * under both sequential and parallel streams (the combiner merges per-thread lists in encounter order; the
 * sort happens once), with and without a {@link KeyEncoder}.
 */
class BeefCollectorsTest {

    private static final KeyEncoder<Integer> INT_ENC = KeyEncoder.ofInt(i -> i);

    private static List<Integer> sample() {
        Random r = new Random(11);
        List<Integer> a = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            a.add(r.nextInt(2000)); // duplicates -> List keeps them, OrderedSet de-dups
        }
        return a;
    }

    private static List<Integer> sortedWithDuplicates() {
        List<Integer> e = new ArrayList<>(sample());
        e.sort(Comparator.naturalOrder());
        return e;
    }

    private static List<Integer> sortedDistinct() {
        return new ArrayList<>(new TreeSet<>(sample()));
    }

    @Test
    void toSortedListSequentialAndParallel() {
        assertEquals(sortedWithDuplicates(),
                sample().stream().collect(BeefCollectors.toSortedList(Comparator.naturalOrder())));
        assertEquals(sortedWithDuplicates(),
                sample().parallelStream().collect(BeefCollectors.toSortedList(Comparator.naturalOrder(), INT_ENC)));
    }

    @Test
    void toOrderedSetDeDupsAndSorts() {
        OrderedSet<Integer> set = sample().stream()
                .collect(BeefCollectors.toOrderedSet(Comparator.naturalOrder(), INT_ENC));
        assertEquals(sortedDistinct(), set.inOrder());
    }

    @Test
    void toOrderedSetParallelIsCorrect() {
        OrderedSet<Integer> set = sample().parallelStream()
                .collect(BeefCollectors.toOrderedSet(Comparator.naturalOrder()));
        assertEquals(sortedDistinct(), set.inOrder());
    }

    @Test
    void toOrderedSetHonorsAccessPolicy() {
        OrderedSet<Integer> set = sample().stream()
                .collect(BeefCollectors.toOrderedSet(Comparator.naturalOrder(), INT_ENC, AccessPolicy.READ_HEAVY));
        assertEquals(sortedDistinct(), set.inOrder(), "still correct");
        assertTrue(set.getStrategy() instanceof AVLStrategy, "READ_HEAVY -> born AVL");
    }
}
