package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.profile.Distribution;
import io.github.richeyworks.superbeefsort.profile.KeyStats;
import io.github.richeyworks.superbeefsort.profile.ProfileDepth;
import io.github.richeyworks.superbeefsort.registry.StrategyRegistry;
import io.github.richeyworks.superbeefsort.select.CostModelStrategySelector;
import io.github.richeyworks.superbeefsort.select.SelectionPolicy;
import io.github.richeyworks.superbeefsort.select.SortPlan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The cost-model selector's choices across constructed profiles. */
class CostModelSelectorTest {

    private final CostModelStrategySelector selector = new CostModelStrategySelector();
    private final StrategyRegistry registry = StrategyRegistry.withDefaults();

    private String pick(DataProfile p) {
        SortPlan plan = selector.select(p, SelectionPolicy.SMART, registry);
        return plan.strategy().value();
    }

    private static DataProfile profile(int n, double sortedness, KeyStats ks) {
        return new DataProfile(n, sortedness, false, ProfileDepth.SHALLOW, n, ks, Distribution.UNKNOWN);
    }

    @Test
    void tinyInputPicksInsertion() {
        assertEquals("insertion", pick(profile(10, 0.5, null)));
    }

    @Test
    void boundedIntegerDataPicksCounting() {
        assertEquals("counting", pick(profile(50_000, 0.5, new KeyStats(0, 50_000, true))));
    }

    @Test
    void wideRangeIntegerDataPicksLearnedOverRadix() {
        // range too wide for counting (countingFeasible = false); the learned bucket sort (~5n,
        // distribution-adaptive, range-agnostic) is cheaper than fixed-pass LSD radix (~8n) and n log n.
        assertEquals("learned", pick(profile(50_000, 0.5, new KeyStats(0, 1L << 30, false))));
    }

    @Test
    void generalDataNoEncoderPicksIntrosort() {
        assertEquals("intro", pick(profile(50_000, 0.5, null)));
    }

    @Test
    void nearlySortedNoEncoderPicksTimsort() {
        assertEquals("jdk.timsort", pick(profile(50_000, 0.99, null)));
    }

    @Test
    void nearlySortedBoundedIntegerStillPicksCounting() {
        // The improvement over the rule-based selector: counting (~2n) beats run-aware TimSort (~14n)
        // even on nearly-sorted data, because counting ignores existing order and is linear.
        assertEquals("counting", pick(profile(50_000, 0.98, new KeyStats(0, 50_000, true))));
    }

    // ---- STABLE policy: prefers the in-place WikiSort for large, mostly-distinct inputs ----

    private String pickStable(DataProfile p) {
        return selector.select(p, SelectionPolicy.STABLE, registry).strategy().value();
    }

    @Test
    void largeDistinctStablePicksWikiSort() {
        // profile() sets distinctEstimate == n, so this is ~all-distinct and above the (2^21) size threshold
        assertEquals("merge.wiki", pickStable(profile(3_000_000, 0.5, null)));
    }

    @Test
    void largeDuplicateHeavyStableStaysMergeSort() {
        // above the size threshold but few distinct values -> WikiSort would only fall back to a rotation
        // merge, so plain merge stays preferred
        DataProfile dupHeavy = new DataProfile(3_000_000, 0.5, true, ProfileDepth.SHALLOW, 100, null, Distribution.UNKNOWN);
        assertEquals("merge", pickStable(dupHeavy));
    }

    @Test
    void smallStableStaysMergeSort() {
        assertEquals("merge", pickStable(profile(1_000, 0.5, null)));
    }

    @Test
    void exactlyAtMemoryBudgetBoundaryPicksWikiSort() {
        // 2^21 elements (profile() makes them all-distinct): merge's LINEAR scratch == the 16 MB budget,
        // so the memory-budgeted gate engages WikiSort (mirrors RuleBasedStrategySelector).
        int n = 1 << 21;
        assertEquals("merge.wiki", pickStable(profile(n, 0.5, null)));
    }

    @Test
    void justBelowMemoryBudgetBoundaryStaysMergeSort() {
        int n = (1 << 21) - 1;
        assertEquals("merge", pickStable(profile(n, 0.5, null)));
    }

    // ---- SMART policy: configurable auxiliary-memory budget excludes over-budget candidates ----

    @Test
    void defaultSelectorHasNoMemoryBudgetAndPicksCounting() {
        // Default ctor == unlimited budget: bounded-integer data still picks the LINEAR-aux counting sort.
        int n = 200_000;
        assertEquals("counting", pick(profile(n, 0.5, new KeyStats(0, 50_000, true))));
    }

    @Test
    void tightMemoryBudgetExcludesLinearSortsAndFallsToIntrosort() {
        // 1 MB cap: every LINEAR-aux sort needs 8n = 1.6 MB > 1 MB at n=200k, so all are excluded and the
        // in-place introsort (negligible LOGARITHMIC stack) wins -- the memory-pressure degradation path.
        CostModelStrategySelector budgeted = new CostModelStrategySelector(1L << 20);
        int n = 200_000;
        DataProfile p = profile(n, 0.5, new KeyStats(0, 50_000, true));
        assertEquals("intro", budgeted.select(p, SelectionPolicy.SMART, registry).strategy().value());
    }

    @Test
    void budgetEqualToCandidateAuxKeepsThatCandidate() {
        // Budget == 8n (counting's exact LINEAR aux at n=200k): the check is inclusive (<=), so counting fits.
        int n = 200_000;
        CostModelStrategySelector budgeted = new CostModelStrategySelector(8L * n);
        DataProfile p = profile(n, 0.5, new KeyStats(0, 50_000, true));
        assertEquals("counting", budgeted.select(p, SelectionPolicy.SMART, registry).strategy().value());
    }

    @Test
    void nonPositiveBudgetIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CostModelStrategySelector(0));
    }

    @Test
    void configuredBudgetAlsoLowersTheStableWikiCrossover() {
        // A 1 MB budget governs STABLE too: merge's 8n scratch reaches it at n=131072, far below the
        // default 16 MB (2^21) crossover, so a budgeted selector prefers the O(1)-aux WikiSort sooner.
        CostModelStrategySelector budgeted = new CostModelStrategySelector(1L << 20);
        int n = 200_000; // 8n = 1.6 MB >= 1 MB budget, but well under the 16 MB default
        DataProfile p = profile(n, 0.5, null); // profile() sets distinctEstimate == n -> mostly distinct
        assertEquals("merge.wiki", budgeted.select(p, SelectionPolicy.STABLE, registry).strategy().value());
        // the default (unbudgeted) selector keeps the fixed 16 MB crossover, so the same input stays on merge
        assertEquals("merge", pickStable(p));
    }
}
