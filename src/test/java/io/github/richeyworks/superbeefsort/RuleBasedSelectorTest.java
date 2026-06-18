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

    // ---- STABLE policy: prefers the in-place WikiSort for large, mostly-distinct inputs ----

    private String pickStable(DataProfile p) {
        return selector.select(p, SelectionPolicy.STABLE, registry).strategy().value();
    }

    private static DataProfile profileDistinct(int n, long distinctEstimate) {
        return new DataProfile(n, 0.5, false, ProfileDepth.SHALLOW, distinctEstimate, null, Distribution.UNKNOWN);
    }

    @Test
    void largeDistinctStablePicksWikiSort() {
        // very large (>= 2^21), ~all distinct -> WikiSort's block-merge engages: stable, O(1) aux, O(n log n)
        assertEquals("merge.wiki", pickStable(profileDistinct(3_000_000, 3_000_000)));
    }

    @Test
    void largeDuplicateHeavyStableStaysMergeSort() {
        // above the size threshold but few distinct values -> WikiSort would only fall back to a rotation
        // merge, so plain merge stays preferred (isolates the distinctness gate from the size gate)
        assertEquals("merge", pickStable(profileDistinct(3_000_000, 100)));
    }

    @Test
    void smallStableStaysMergeSort() {
        // below the size threshold, plain merge sort's O(n) scratch is not worth avoiding
        assertEquals("merge", pickStable(profileDistinct(1_000, 1_000)));
    }
}
