package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.profile.Distribution;
import io.github.richeyworks.superbeefsort.profile.ProfileDepth;
import io.github.richeyworks.superbeefsort.registry.StrategyRegistry;
import io.github.richeyworks.superbeefsort.select.RuleBasedStrategySelector;
import io.github.richeyworks.superbeefsort.select.SelectionPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The default rule-based selector, including the run-aware path that uses {@code longestRun}. */
class RuleBasedSelectorTest {

    private final RuleBasedStrategySelector selector = new RuleBasedStrategySelector();
    private final StrategyRegistry registry = StrategyRegistry.withDefaults();

    private String pick(DataProfile p) {
        return selector.select(p, SelectionPolicy.SMART, registry).strategy().value();
    }

    private static DataProfile profile(int n, double sortedness, int longestRun) {
        return new DataProfile(n, sortedness, false, ProfileDepth.SHALLOW, n, null, Distribution.UNKNOWN, longestRun);
    }

    @Test
    void tinyInputPicksSortingNetwork() {
        assertEquals("network.oddeven", pick(profile(12, 0.5, 4)));
    }

    @Test
    void highAdjacencyPicksTimsort() {
        assertEquals("jdk.timsort", pick(profile(1000, 0.95, 50)));
    }

    @Test
    void longSingleRunPicksTimsortEvenWhenAdjacencyIsBelowThreshold() {
        // adjacency 0.85 (< 0.90, so not "nearlySorted"), but the longest run covers 60% -> TimSort
        assertEquals("jdk.timsort", pick(profile(1000, 0.85, 600)));
    }

    @Test
    void shortRunsAndNoKeysPicksIntrosort() {
        // not nearly sorted, longest run only 10%, no integer keys -> general comparison path
        assertEquals("intro", pick(profile(1000, 0.5, 100)));
    }
}
