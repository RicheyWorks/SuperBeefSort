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

    // ---- SMART soft auxiliary-memory penalty (graded, vs the hard over-budget filter) ----

    @Test
    void tinyAuxPenaltyDoesNotFlipTheChoice() {
        // lambda = 0.001/byte adds only ~1600 to counting's ~250k cost: far too little to overtake the
        // introsort baseline, so the graded penalty leaves the fast choice in place.
        CostModelStrategySelector lightlyPenalized = CostModelStrategySelector.withAuxPenalty(0.001);
        int n = 200_000;
        DataProfile p = profile(n, 0.5, new KeyStats(0, 50_000, true));
        assertEquals("counting", lightlyPenalized.select(p, SelectionPolicy.SMART, registry).strategy().value());
    }

    @Test
    void heavyAuxPenaltyFlipsLinearCountingToInPlaceIntrosort() {
        // lambda = 3/byte: counting's 8n = 1.6 MB LINEAR scratch costs 4.8M extra, lifting it past the
        // LOGARITHMIC introsort (negligible stack). The flip threshold is ~2.045/byte (verified), so 3 clears
        // it -- the graded analog of the hard budget's exclusion.
        CostModelStrategySelector penalized = CostModelStrategySelector.withAuxPenalty(3.0);
        int n = 200_000;
        DataProfile p = profile(n, 0.5, new KeyStats(0, 50_000, true));
        assertEquals("intro", penalized.select(p, SelectionPolicy.SMART, registry).strategy().value());
    }

    @Test
    void penaltyComposesWithBudgetAndActsEvenWithinIt() {
        // Budget == 8n keeps counting (it fits, inclusive) -- proven by budgetEqualToCandidateAuxKeepsThatCandidate.
        // Adding a lambda=3 penalty to the same budget still flips to introsort, so the soft penalty
        // discriminates among in-budget candidates the hard cap leaves untouched. The two knobs compose.
        int n = 200_000;
        DataProfile p = profile(n, 0.5, new KeyStats(0, 50_000, true));
        assertEquals("counting",
                new CostModelStrategySelector(8L * n).select(p, SelectionPolicy.SMART, registry).strategy().value());
        assertEquals("intro",
                new CostModelStrategySelector(8L * n, 3.0).select(p, SelectionPolicy.SMART, registry).strategy().value());
    }

    @Test
    void negativeOrNaNAuxPenaltyIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CostModelStrategySelector.withAuxPenalty(-0.1));
        assertThrows(IllegalArgumentException.class, () -> CostModelStrategySelector.withAuxPenalty(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new CostModelStrategySelector(1L << 20, -1.0));
    }

    // ---- global inversion signal refines the TimSort run-cost (galloping) ----

    /** A profile carrying an exact, measured global inversion count (other fields neutral). */
    private static DataProfile profileInv(int n, double sortedness, long inversions) {
        return new DataProfile(n, sortedness, false, ProfileDepth.DEEP, n, null, Distribution.UNKNOWN,
                0, inversions, true);
    }

    @Test
    void lowGlobalInversionsLetTimsortWinWhereAdjacencyAloneWouldNot() {
        // At 0.70 adjacency sortedness the adjacency-only run cost exceeds introsort (so the old model picked
        // introsort here), but a low *global* inversion count (10% of max) shows the data is genuinely ordered,
        // so galloping discounts TimSort's merges and it wins.
        int n = 50_000;
        long maxInv = (long) n * (n - 1) / 2;
        DataProfile globallyOrdered = profileInv(n, 0.70, (long) (0.10 * maxInv));
        assertEquals("jdk.timsort",
                selector.select(globallyOrdered, SelectionPolicy.SMART, registry).strategy().value());
    }

    @Test
    void highGlobalInversionsKeepIntrosortAtTheSameAdjacency() {
        // Same 0.70 adjacency sortedness, but a high global inversion count (70% of max) reveals the data is
        // globally disordered (locally tidy, far-apart swaps): the galloping discount is suppressed, so TimSort
        // stays costlier than introsort. The inversion signal distinguishes two inputs the adjacency-only model
        // scored identically.
        int n = 50_000;
        long maxInv = (long) n * (n - 1) / 2;
        DataProfile globallyDisordered = profileInv(n, 0.70, (long) (0.70 * maxInv));
        assertEquals("intro",
                selector.select(globallyDisordered, SelectionPolicy.SMART, registry).strategy().value());
    }
}
