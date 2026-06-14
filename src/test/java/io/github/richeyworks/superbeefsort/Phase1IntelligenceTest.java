package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.engine.SortRunResult;
import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.profile.Distribution;
import io.github.richeyworks.superbeefsort.profile.IntelligentDataProfiler;
import io.github.richeyworks.superbeefsort.profile.ProfileDepth;
import io.github.richeyworks.superbeefsort.select.SelectionPolicy;
import io.github.richeyworks.superbeefsort.strategy.CountingSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.RadixSortStrategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 1: the profiler's intelligence and the non-comparison sorts working end to end. */
class Phase1IntelligenceTest {

    record Item(int key, int seq) { }

    @Test
    void countingAndRadixAreStable() {
        Random rnd = new Random(7);
        List<Item> data = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            data.add(new Item(rnd.nextInt(5), i)); // few distinct keys -> many ties to expose instability
        }
        Comparator<Item> byKey = Comparator.comparingInt(Item::key);
        KeyEncoder<Item> encoder = KeyEncoder.ofInt(Item::key);

        List<SortStrategy<Item>> strategies = List.of(new CountingSortStrategy<>(), new RadixSortStrategy<>());
        for (SortStrategy<Item> strategy : strategies) {
            SortBuffer<Item> buffer = SortBuffer.of(data, byKey, encoder);
            strategy.sort(buffer, SortContext.noop());
            List<Item> out = buffer.toList();
            for (int i = 1; i < out.size(); i++) {
                Item a = out.get(i - 1);
                Item b = out.get(i);
                assertTrue(a.key() < b.key() || (a.key() == b.key() && a.seq() < b.seq()),
                        "stability violated by " + strategy.id() + " at " + i + ": " + a + " then " + b);
            }
        }
    }

    @Test
    void profilerEstimatesDistinctCountAndKeyStats() {
        List<Integer> data = new ArrayList<>();
        for (int rep = 0; rep < 10; rep++) {
            for (int k = 0; k < 1_000; k++) {
                data.add(k); // 1000 distinct keys, each repeated 10x
            }
        }
        Collections.shuffle(data, new Random(1));

        SortBuffer<Integer> buffer =
                SortBuffer.of(data, Comparator.<Integer>naturalOrder(), KeyEncoder.ofInt(i -> i));
        DataProfile p = new IntelligentDataProfiler<Integer>().profile(buffer, ProfileDepth.SHALLOW);

        assertNotNull(p.keyStats(), "key stats should be present for a faithful encoder");
        assertEquals(0, p.keyStats().min());
        assertEquals(999, p.keyStats().max());
        assertTrue(p.keyStats().countingFeasible());
        assertEquals(Distribution.UNIFORM, p.distribution());

        long est = p.distinctEstimate();
        assertTrue(est > 850 && est < 1150, "HLL distinct estimate out of tolerance: " + est);
    }

    @Test
    void engineAutoSelectsNonComparisonForIntegerData() {
        Random rnd = new Random(99);
        List<Integer> data = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            data.add(rnd.nextInt(20_000));
        }

        OrderedSet<Integer> set = OrderedSet.withNaturalOrder(new RedBlackStrategy<Integer>());
        SortRunResult<Integer> result = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(data)
                .keyEncoder(KeyEncoder.ofInt(i -> i))
                .policy(SelectionPolicy.SMART)
                .feedInto(set);

        String chosen = result.plan().strategy().value();
        assertTrue(chosen.equals("counting") || chosen.equals("radix.lsd"),
                "expected a non-comparison sort, got " + chosen);

        TreeSet<Integer> reference = new TreeSet<>(data);
        assertEquals(new ArrayList<>(reference), set.inOrder());
        assertTrue(result.feedResult().healthy());
    }
}
