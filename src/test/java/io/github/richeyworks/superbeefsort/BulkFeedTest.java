package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.superbeefsort.core.KeyEncoder;
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

/** The O(n) bulk-build fast path: an empty OrderedSet target is built via CSRBT fromSorted. */
class BulkFeedTest {

    @Test
    void feedsEmptyOrderedSetViaBulkFastPath() {
        Random rnd = new Random(123);
        List<Integer> data = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            data.add(rnd.nextInt(20_000));
        }

        OrderedSet<Integer> set = OrderedSet.withNaturalOrder(new RedBlackStrategy<Integer>());
        SortRunResult<Integer> r = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(data)
                .keyEncoder(KeyEncoder.ofInt(i -> i))
                .policy(SelectionPolicy.SMART)
                .feedInto(set);

        assertEquals(FeedMode.BULK, r.feedResult().mode(), "empty OrderedSet should use the O(n) bulk path");

        TreeSet<Integer> reference = new TreeSet<>(data);
        assertEquals(new ArrayList<>(reference), set.inOrder());
        assertEquals(reference.size(), set.size());
        assertEquals(reference.first(), set.minimum());
        assertEquals(reference.last(), set.maximum());
        assertEquals(reference.size(), r.feedResult().inserted(), "inserted distinct count");
        assertEquals(data.size() - reference.size(), r.feedResult().duplicates(), "duplicate count");
        assertTrue(r.feedResult().healthy());
    }

    @Test
    void fallsBackToBalancedForNonEmptyTarget() {
        OrderedSet<Integer> set = OrderedSet.withNaturalOrder(new RedBlackStrategy<Integer>());
        set.add(99_999); // pre-populate so the bulk fast path does not apply

        SortRunResult<Integer> r = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(new ArrayList<>(List.of(5, 3, 1, 4, 2)))
                .policy(SelectionPolicy.SMART)
                .feedInto(set);

        assertEquals(FeedMode.BALANCED, r.feedResult().mode(), "non-empty target should fall back to balanced add");
        assertEquals(List.of(1, 2, 3, 4, 5, 99_999), set.inOrder());
    }
}
