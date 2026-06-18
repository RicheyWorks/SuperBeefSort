package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.superbeefsort.core.SortResult;
import io.github.richeyworks.superbeefsort.core.StrategyId;
import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.profile.Distribution;
import io.github.richeyworks.superbeefsort.profile.KeyStats;
import io.github.richeyworks.superbeefsort.profile.ProfileDepth;
import io.github.richeyworks.superbeefsort.registry.StrategyRegistry;
import io.github.richeyworks.superbeefsort.select.BanditStrategySelector;
import io.github.richeyworks.superbeefsort.select.SelectionPolicy;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The self-tuning selector: it should converge to the genuinely cheapest arm per context (even when
 * that contradicts the analytical prior), respect capability gating, keep contexts independent, and
 * defer to the base selector for the deterministic policies.
 */
class BanditSelectorTest {

    private final StrategyRegistry registry = StrategyRegistry.withDefaults();

    private static DataProfile profile(int n, double sortedness, KeyStats ks) {
        return new DataProfile(n, sortedness, false, ProfileDepth.SHALLOW, n, ks, Distribution.UNKNOWN);
    }

    private static SortResult outcome(StrategyId id, int n, double cost) {
        return new SortResult(id, n, (long) cost, 0L, 0L); // cost = comparisons + moves (default cost fn)
    }

    /** Drive the closed loop: select -> run (synthetic cost) -> observe, for {@code rounds} jobs. */
    private static void train(BanditStrategySelector bandit, StrategyRegistry reg, DataProfile p,
                              Map<String, Double> truth, double otherwise, int rounds) {
        for (int i = 0; i < rounds; i++) {
            StrategyId id = bandit.select(p, SelectionPolicy.SMART, reg).strategy();
            double c = truth.getOrDefault(id.value(), otherwise);
            bandit.observe(p, id, outcome(id, p.size(), c));
        }
    }

    @Test
    void convergesToCheapestEvenAgainstThePrior() {
        BanditStrategySelector bandit = new BanditStrategySelector();
        DataProfile p = profile(50_000, 0.5, null); // comparison-only, random
        // The prior favors quicksort/intro (n log n); reality says run-aware TimSort is far cheaper here.
        Map<String, Double> truth = Map.of(
                "jdk.timsort", 100_000.0,
                "intro", 780_000.0,
                "quick.threeway", 800_000.0,
                "merge", 820_000.0,
                "heap", 1_200_000.0);
        train(bandit, registry, p, truth, 900_000.0, 300);

        assertEquals("jdk.timsort", bandit.select(p, SelectionPolicy.SMART, registry).strategy().value(),
                "should have learned TimSort is cheapest despite the n log n prior");
        assertTrue(bandit.meanCost(p, StrategyId.of("jdk.timsort")) < bandit.meanCost(p, StrategyId.of("intro")),
                "learned mean cost should rank TimSort below intro");
    }

    @Test
    void prefersCountingForBoundedIntegerKeys() {
        BanditStrategySelector bandit = new BanditStrategySelector();
        DataProfile p = profile(50_000, 0.5, new KeyStats(0, 40_000, true));
        Map<String, Double> truth = Map.of(
                "counting", 90_000.0,
                "radix.lsd", 400_000.0,
                "intro", 780_000.0);
        train(bandit, registry, p, truth, 700_000.0, 200);

        assertEquals("counting", bandit.select(p, SelectionPolicy.SMART, registry).strategy().value());
    }

    @Test
    void neverExploresNonComparisonSortsWithoutKeyStats() {
        BanditStrategySelector bandit = new BanditStrategySelector();
        DataProfile p = profile(50_000, 0.5, null); // no key encoder
        // Even while (futilely) making the non-comparison sorts look cheap, they must never be chosen.
        for (int i = 0; i < 200; i++) {
            StrategyId id = bandit.select(p, SelectionPolicy.SMART, registry).strategy();
            assertNotEquals("counting", id.value());
            assertNotEquals("radix.lsd", id.value());
            boolean pretendCheap = id.value().equals("counting") || id.value().equals("radix.lsd");
            bandit.observe(p, id, outcome(id, p.size(), pretendCheap ? 1.0 : 500_000.0));
        }
    }

    @Test
    void neverExploresInsertionOnLargeInputs() {
        BanditStrategySelector bandit = new BanditStrategySelector();
        DataProfile p = profile(50_000, 0.5, null);
        for (int i = 0; i < 200; i++) {
            StrategyId id = bandit.select(p, SelectionPolicy.SMART, registry).strategy();
            assertNotEquals("insertion", id.value(), "O(n^2) insertion must not be an arm at n=50k");
            bandit.observe(p, id, outcome(id, p.size(), 500_000.0));
        }
    }

    @Test
    void contextsLearnIndependently() {
        BanditStrategySelector bandit = new BanditStrategySelector();
        DataProfile big = profile(50_000, 0.5, null);
        // Teach the "large random comparable" context that heap happens to win here.
        train(bandit, registry, big, Map.of("heap", 50_000.0), 900_000.0, 300);
        assertEquals("heap", bandit.select(big, SelectionPolicy.SMART, registry).strategy().value());

        // A different (tiny) context must not inherit that; it follows its own evidence/priors.
        DataProfile tiny = profile(10, 0.5, null);
        assertNotEquals("heap", bandit.select(tiny, SelectionPolicy.SMART, registry).strategy().value());
    }

    @Test
    void deterministicPoliciesDelegateToBase() {
        BanditStrategySelector bandit = new BanditStrategySelector();
        DataProfile p = profile(50_000, 0.5, null);
        assertEquals("intro", bandit.select(p, SelectionPolicy.FIXED_INTRO, registry).strategy().value());
        assertEquals("merge", bandit.select(p, SelectionPolicy.STABLE, registry).strategy().value());
    }

    @Test
    void stablePolicyRoutesLargeDistinctToWikiSort() {
        // STABLE delegates to the rule-based base, which prefers the in-place WikiSort for large,
        // mostly-distinct inputs (profile() sets distinctEstimate == n).
        BanditStrategySelector bandit = new BanditStrategySelector();
        DataProfile big = profile(200_000, 0.5, null);
        assertEquals("merge.wiki", bandit.select(big, SelectionPolicy.STABLE, registry).strategy().value());
    }
}
