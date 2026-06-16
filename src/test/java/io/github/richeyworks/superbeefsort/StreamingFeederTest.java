package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.superbeefsort.feed.FeedMode;
import io.github.richeyworks.superbeefsort.feed.FeedResult;
import io.github.richeyworks.superbeefsort.feed.HealthPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Streaming / bounded" path (docs/architecture-csrbt-integration.md §2.3): BeefSort.streaming sets the
 * target's sliding-window capacity and feeds the sorted run in batches with self-heal backpressure. The run
 * is ascending and CSRBT FIFO-evicts the oldest-inserted (smallest) key, so a capped stream converges to the
 * largest {@code maxSize} distinct keys — a deterministic top-N.
 */
class StreamingFeederTest {

    private static List<Integer> sample() {
        Random rng = new Random(29);
        List<Integer> a = new ArrayList<>();
        for (int i = 0; i < 8000; i++) {
            a.add(rng.nextInt(5000)); // ~3990 distinct, with duplicates
        }
        return a;
    }

    private static List<Integer> sortedDistinct() {
        return new ArrayList<>(new TreeSet<>(sample()));
    }

    private static OrderedSet<Integer> emptySet() {
        return OrderedSet.withNaturalOrder(new RedBlackStrategy<Integer>());
    }

    @Test
    void boundedStreamRetainsTopWindow() {
        OrderedSet<Integer> set = emptySet();
        int cap = 500;
        FeedResult r = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(sample())
                .streaming(set, cap);

        List<Integer> distinct = sortedDistinct();
        List<Integer> expected = distinct.subList(distinct.size() - cap, distinct.size()); // FIFO evicts smallest

        assertEquals(FeedMode.STREAMING, r.mode());
        assertEquals(cap, set.getMaxSize(), "window capacity is set");
        assertEquals(cap, set.size(), "bounded to the window");
        assertEquals(expected, set.inOrder(), "retains the largest cap distinct keys");
        assertTrue(r.inserted() >= cap, "more keys were inserted than the window holds (then evicted)");
    }

    @Test
    void unboundedStreamKeepsEveryDistinctKey() {
        OrderedSet<Integer> set = emptySet();
        FeedResult r = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(sample())
                .streaming(set, 0); // unbounded

        assertEquals(0, set.getMaxSize());
        assertEquals(sortedDistinct(), set.inOrder());
        assertEquals(FeedMode.STREAMING, r.mode());
    }

    @Test
    void healthPolicyDrivesSelfHealBackpressure() {
        OrderedSet<Integer> set = emptySet();
        FeedResult r = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(sample())
                .withHealthPolicy(new HealthPolicy(256, 1)) // validate after every 256-add batch
                .streaming(set, 1000);

        assertTrue(r.healthChecks() > 0, "periodic validation ran");
        assertTrue(r.healthy(), "the streamed window stayed healthy");
        assertEquals(1000, set.size());
    }

    @Test
    void throughputPolicySkipsValidation() {
        OrderedSet<Integer> set = emptySet();
        FeedResult r = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(sample())
                .withHealthPolicy(HealthPolicy.throughput(1024)) // validateEvery = 0
                .streaming(set, 1000);

        assertEquals(0, r.healthChecks(), "no validation when validateEvery == 0");
        assertEquals(1000, set.size());
    }
}
