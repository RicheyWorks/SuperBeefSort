package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.superbeefsort.engine.SortRunResult;
import io.github.richeyworks.superbeefsort.feed.FeedMode;
import io.github.richeyworks.superbeefsort.select.SelectionPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end: SuperBeefSort sorts random data and feeds a real CSRBT {@code OrderedSet}. */
class EngineFeedCsrbtTest {

    @Test
    void sortsAndFeedsRealOrderedSetInOrder() {
        Random rnd = new Random(42);
        List<Integer> data = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            data.add(rnd.nextInt(20_000));
        }

        OrderedSet<Integer> set = OrderedSet.withNaturalOrder(new RedBlackStrategy<Integer>());

        SortRunResult<Integer> result = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(data)
                .policy(SelectionPolicy.SMART)
                .feedInto(set);

        TreeSet<Integer> reference = new TreeSet<>(data);
        List<Integer> referenceList = new ArrayList<>(reference);

        assertEquals(reference.size(), set.size(), "distinct count");
        assertEquals(referenceList, set.inOrder(), "in-order traversal matches sorted distinct keys");
        assertTrue(result.wasFed());
        assertTrue(result.feedResult().healthy());

        // CSRBT order statistics line up with the reference (select is 1-indexed).
        assertEquals(referenceList.get(0), set.minimum());
        assertEquals(referenceList.get(referenceList.size() - 1), set.maximum());
        int mid = referenceList.size() / 2;
        assertEquals(referenceList.get(mid), set.select(mid + 1), "order statistic");
    }

    @Test
    void healthGatedFeedKeepsReverseSortedInputValid() {
        List<Integer> data = new ArrayList<>();
        for (int i = 2_000; i >= 1; i--) {
            data.add(i); // worst-case reverse-sorted input
        }

        OrderedSet<Integer> set = OrderedSet.withNaturalOrder(new RedBlackStrategy<Integer>());

        SortRunResult<Integer> result = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(data)
                .feedMode(FeedMode.HEALTH_GATED)
                .feedInto(set);

        assertTrue(result.feedResult().healthy());
        assertTrue(result.feedResult().healthChecks() >= 1);
        assertEquals(2_000, set.size());

        List<Integer> expected = new ArrayList<>();
        for (int i = 1; i <= 2_000; i++) {
            expected.add(i);
        }
        assertEquals(expected, set.inOrder());
    }
}
