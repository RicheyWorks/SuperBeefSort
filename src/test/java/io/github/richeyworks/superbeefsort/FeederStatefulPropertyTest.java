package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stateful property: feeding many SuperBeefSort sort-runs into the SAME live CSRBT {@code OrderedSet}
 * keeps it consistent and structurally valid after every batch. The first feed (empty set) takes CSRBT's
 * O(n) {@code buildFromSorted} bulk path; later feeds (non-empty) take the median-first {@code add} path —
 * so one sequence exercises both, and the red-black invariants must hold throughout (checked via
 * {@code selfRepair()}). jqwik shrinks any failing sequence to a minimal one.
 */
class FeederStatefulPropertyTest {

    @Property(tries = 100)
    void repeatedFeedsKeepCsrbtConsistentAndValid(@ForAll("batches") List<List<Integer>> batches) {
        OrderedSet<Integer> set = OrderedSet.withNaturalOrder(new RedBlackStrategy<Integer>());
        TreeSet<Integer> expected = new TreeSet<>();

        for (List<Integer> batch : batches) {
            BeefSort.with(Comparator.<Integer>naturalOrder())
                    .source(new ArrayList<>(batch))
                    .feedInto(set);
            expected.addAll(batch);

            assertEquals(expected.size(), set.size(), "distinct count after a feed");
            assertEquals(new ArrayList<>(expected), set.inOrder(), "in-order == cumulative sorted distinct");
            assertTrue(set.selfRepair(), "red-black invariants hold after the feed");
        }
    }

    @Provide
    Arbitrary<List<List<Integer>>> batches() {
        Arbitrary<List<Integer>> oneBatch = Arbitraries.integers().between(-500, 500).list().ofMaxSize(200);
        return oneBatch.list().ofMinSize(1).ofMaxSize(8); // a sequence of up to 8 feed-batches
    }
}
