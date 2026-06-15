package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.superbeefsort.feed.CsrbtTarget;
import io.github.richeyworks.superbeefsort.feed.FeedMode;
import io.github.richeyworks.superbeefsort.feed.FeedResult;
import io.github.richeyworks.superbeefsort.feed.PrecisionFeeder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PrecisionFeeder: median-first insertion that validates CSRBT health after every insert and counts
 * duplicates explicitly, leaving a correct, valid OrderedSet.
 */
class PrecisionFeederTest {

    @Test
    void validatesEveryInsertAndCountsDuplicates() {
        List<Integer> sortedRun = List.of(1, 2, 3, 3, 4, 5, 6, 7, 8, 9, 10); // 3 repeats once
        OrderedSet<Integer> set = OrderedSet.withNaturalOrder(new RedBlackStrategy<Integer>());

        FeedResult r = new PrecisionFeeder<Integer>().feed(sortedRun, CsrbtTarget.of(set));

        assertEquals(FeedMode.PRECISION, r.mode());
        assertEquals(sortedRun.size(), r.presented());
        assertEquals(10, r.inserted(), "ten distinct keys inserted");
        assertEquals(1, r.duplicates(), "the repeated 3 is counted, not dropped silently");
        assertEquals(sortedRun.size(), r.healthChecks(), "a health check after every insert");
        assertTrue(r.healthy(), "the tree stays valid throughout");

        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), set.inOrder());
        assertEquals(10, set.size());
    }

    @Test
    void feedsLargerDistinctRunCorrectly() {
        List<Integer> run = new ArrayList<>();
        for (int i = 1; i <= 200; i++) {
            run.add(i);
        }
        OrderedSet<Integer> set = OrderedSet.withNaturalOrder(new RedBlackStrategy<Integer>());

        FeedResult r = new PrecisionFeeder<Integer>().feed(run, CsrbtTarget.of(set));

        assertEquals(200, r.inserted());
        assertEquals(0, r.duplicates());
        assertEquals(200, r.healthChecks(), "validated after every one of the 200 inserts");
        assertTrue(r.healthy());
        assertEquals(run, set.inOrder());
        assertEquals(Integer.valueOf(1), set.minimum());
        assertEquals(Integer.valueOf(200), set.maximum());
    }
}
