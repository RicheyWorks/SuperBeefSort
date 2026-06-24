package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.profile.Distribution;
import io.github.richeyworks.superbeefsort.profile.KeyStats;
import io.github.richeyworks.superbeefsort.profile.ProfileDepth;
import io.github.richeyworks.superbeefsort.registry.StrategyRegistry;
import io.github.richeyworks.superbeefsort.select.RuleBasedStrategySelector;
import io.github.richeyworks.superbeefsort.select.SelectionPolicy;
import io.github.richeyworks.superbeefsort.strategy.ParallelRadixSortStrategy;
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

    // ---- SMART policy: large wide-range integer inputs route to the multi-threaded radix.lsd.parallel ----
    // Provisional crossover == ParallelRadixSortStrategy.PARALLEL_THRESHOLD (pending the host JMH sweep);
    // the parallel sort is byte-for-byte identical to sequential radix.lsd, so only wall-clock changes.

    /** A wide-range integer-keyed profile (sortedness 0.5 so the run-aware/insertion branches don't fire). */
    private static DataProfile profileKeyed(int n, KeyStats ks) {
        return new DataProfile(n, 0.5, false, ProfileDepth.SHALLOW, n, ks, Distribution.UNKNOWN);
    }

    @Test
    void largeWideRangeIntegerPicksParallelRadix() {
        // range too wide for counting (span 2^30 > max(2^16, 4n)); n above the crossover -> parallel LSD radix
        int n = 100_000;
        assertEquals("radix.lsd.parallel", pick(profileKeyed(n, new KeyStats(0, 1L << 30, false))));
    }

    @Test
    void exactlyAtCrossoverPicksParallelRadix() {
        // n == the parallel engage threshold: the multi-threaded path begins exactly here
        int n = ParallelRadixSortStrategy.PARALLEL_THRESHOLD;
        assertEquals("radix.lsd.parallel", pick(profileKeyed(n, new KeyStats(0, 1L << 30, false))));
    }

    @Test
    void justBelowCrossoverStaysSequentialRadix() {
        // one element under the threshold: below it the parallel strategy would only run single-threaded,
        // so the selector keeps sequential radix.lsd rather than pay pointless thread-setup overhead
        int n = ParallelRadixSortStrategy.PARALLEL_THRESHOLD - 1;
        assertEquals("radix.lsd", pick(profileKeyed(n, new KeyStats(0, 1L << 30, false))));
    }

    @Test
    void largeBoundedRangeIntegerStillPicksCounting() {
        // above the crossover but a bounded range (span <= max(2^16, 4n)): counting is O(n) and wins, so
        // the new parallel-radix routing must not cannibalize the counting branch (it sits after it)
        int n = 100_000;
        assertEquals("counting", pick(profileKeyed(n, new KeyStats(0, 50_000, true))));
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

    @Test
    void exactlyAtMemoryBudgetBoundaryPicksWikiSort() {
        // 2^21 distinct elements: merge's LINEAR scratch == the 16 MB budget, so the memory-budgeted
        // gate engages WikiSort (reproduces the old `size >= 2^21` threshold byte-for-byte).
        int n = 1 << 21;
        assertEquals("merge.wiki", pickStable(profileDistinct(n, n)));
    }

    @Test
    void justBelowMemoryBudgetBoundaryStaysMergeSort() {
        // one element under 2^21: merge's scratch is below budget, so plain merge stays preferred.
        int n = (1 << 21) - 1;
        assertEquals("merge", pickStable(profileDistinct(n, n)));
    }
}
