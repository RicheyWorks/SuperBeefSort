package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.strategy.AVLStrategy;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.csrbt.strategy.SplayStrategy;
import io.github.richeyworks.csrbt.strategy.WeightBalancedStrategy;
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
 * "Construct, don't insert" (docs/architecture-csrbt-integration.md): BeefSort.buildOrderedSet sorts and
 * builds a CSRBT OrderedSet directly via fromSorted, born with the access-pattern-advised strategy.
 */
class BuildOrderedSetTest {

    private static List<Integer> sample() {
        Random rng = new Random(11);
        List<Integer> a = new ArrayList<>();
        for (int i = 0; i < 4000; i++) {
            a.add(rng.nextInt(2500)); // duplicates
        }
        return a;
    }

    private static List<Integer> sortedDistinct() {
        return new ArrayList<>(new TreeSet<>(sample()));
    }

    private static OrderedSet<Integer> build(AccessPolicy policy) {
        return BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(new ArrayList<>(sample()))
                .accessPattern(policy)
                .buildOrderedSet();
    }

    @Test
    void bornWithAdvisedStrategyAndCorrectContents() {
        List<Integer> expected = sortedDistinct();

        OrderedSet<Integer> balanced = build(AccessPolicy.BALANCED);
        assertTrue(balanced.getStrategy() instanceof RedBlackStrategy, "BALANCED -> RedBlack");
        assertEquals(expected, balanced.inOrder(), "contents == sorted distinct");
        assertEquals(expected.size(), balanced.size());
        assertEquals(expected.get(0), balanced.minimum());
        assertEquals(expected.get(expected.size() - 1), balanced.maximum());

        assertTrue(build(AccessPolicy.READ_HEAVY).getStrategy() instanceof AVLStrategy, "READ_HEAVY -> AVL");
        assertTrue(build(AccessPolicy.SKEWED).getStrategy() instanceof SplayStrategy, "SKEWED -> Splay");
        assertTrue(build(AccessPolicy.WRITE_HEAVY).getStrategy() instanceof WeightBalancedStrategy, "WRITE_HEAVY -> WB");
    }

    @Test
    void defaultIsBalancedAndUnchanged() {
        OrderedSet<Integer> set = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(new ArrayList<>(sample()))
                .buildOrderedSet(); // no accessPattern -> BALANCED
        assertTrue(set.getStrategy() instanceof RedBlackStrategy);
        assertEquals(sortedDistinct(), set.inOrder());
    }

    @Test
    void targetStrategyOverrideBeatsAdvisor() {
        OrderedSet<Integer> set = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(new ArrayList<>(sample()))
                .accessPattern(AccessPolicy.READ_HEAVY)     // would advise AVL
                .targetStrategy(() -> new SplayStrategy<Integer>()) // but the override wins
                .buildOrderedSet();
        assertTrue(set.getStrategy() instanceof SplayStrategy);
        assertEquals(sortedDistinct(), set.inOrder());
    }
}
