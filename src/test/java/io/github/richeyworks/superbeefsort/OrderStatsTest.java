package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.ensemble.EnsembleOrderedSet;
import io.github.richeyworks.superbeefsort.csrbt.OrderStats;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gap 4 of docs/adr-csrbt-integration-deepening.md: {@link OrderStats} surfaces CSRBT's order statistics as
 * the payoff of feeding — uniformly over a {@code buildOrderedSet()} result (already a {@code RankedSet}) and
 * over a fed {@code EnsembleOrderedSet} (which exposes only {@code OrderedCollection}, so its stats are
 * otherwise reachable only via its current primary). Built from a shuffled 0..99, so every statistic has a
 * known answer.
 */
class OrderStatsTest {

    private static List<Integer> shuffled0to99() {
        List<Integer> data = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            data.add(i);
        }
        Collections.shuffle(data, new Random(11));
        return data;
    }

    /** The 1-indexed, lower-median, closed-range contract on the keys 0..99. */
    private static void assertStatsOver0to99(OrderStats<Integer> s) {
        assertEquals(100, s.size());
        assertEquals(0, s.minimum());
        assertEquals(99, s.maximum());
        assertEquals(0, s.select(1));        // 1-indexed
        assertEquals(49, s.select(50));
        assertEquals(99, s.select(100));
        assertEquals(1, s.rank(0));
        assertEquals(50, s.rank(49));
        assertEquals(100, s.rank(99));
        assertEquals(49, s.median());        // lower median of an even count
        assertEquals(51, s.successor(50));
        assertEquals(49, s.predecessor(50));
        assertNull(s.successor(99), "no key > 99");
        assertNull(s.predecessor(0), "no key < 0");
        assertEquals(10, s.countInRange(10, 19));
        assertEquals(List.of(10, 11, 12), s.rangeQuery(10, 12));
        int p50 = s.percentile(50);          // exact formula is CSRBT's; just bound it
        assertTrue(p50 >= 0 && p50 <= 99, "percentile(50) in range: " + p50);
    }

    @Test
    void orderStatsOverABuiltOrderedSet() {
        OrderedSet<Integer> set = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(shuffled0to99())
                .buildOrderedSet();
        assertStatsOver0to99(OrderStats.of(set));
    }

    @Test
    void orderStatsOverAFedEnsemble() {
        EnsembleOrderedSet<Integer> ensemble = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(shuffled0to99())
                .buildEnsemble();
        // The ensemble itself exposes only OrderedCollection; OrderStats reaches its primary member's RankedSet.
        assertStatsOver0to99(OrderStats.ofEnsemble(ensemble));
    }
}
