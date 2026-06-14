package io.github.richeyworks.superbeefsort.select;

import io.github.richeyworks.superbeefsort.core.StrategyId;
import io.github.richeyworks.superbeefsort.feed.FeedMode;
import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.profile.KeyStats;
import io.github.richeyworks.superbeefsort.registry.StrategyRegistry;
import io.github.richeyworks.superbeefsort.strategy.CountingSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.InsertionSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.IntroSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.JdkSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.MergeSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.RadixSortStrategy;

/**
 * Selects by estimating each applicable strategy's cost from the {@link DataProfile} and choosing the
 * cheapest, rather than applying fixed thresholds. The estimates are deliberately coarse — relative
 * order is what matters:
 *
 * <ul>
 *   <li>introsort (robust comparison sort): {@code n log2 n}</li>
 *   <li>run-aware TimSort: {@code 1.3 · n · log2(runs)}, with runs inferred from sortedness</li>
 *   <li>counting (needs a faithful integer key in a bounded range): {@code n + range}</li>
 *   <li>LSD radix (needs a faithful integer key): {@code ~8 · n} (fixed byte passes)</li>
 * </ul>
 *
 * <p>This naturally reproduces the rule-based choices and improves on them where they diverge — e.g.
 * for nearly-sorted <em>integer</em> data it picks linear counting (~2n) over TimSort (~14n). Pluggable
 * behind {@link StrategySelector}; the engine default remains {@link RuleBasedStrategySelector}.</p>
 */
public final class CostModelStrategySelector implements StrategySelector {

    private static final double LN2 = Math.log(2);
    private static final double RADIX_PASSES = 8.0;       // signed 64-bit keys -> ~8 byte passes
    private static final double TIMSORT_OVERHEAD = 1.3;   // merge buffer allocations vs in-place sorts

    @Override
    public SortPlan select(DataProfile p, SelectionPolicy policy, StrategyRegistry registry) {
        StrategyId fallback = IntroSortStrategy.ID;
        if (policy == SelectionPolicy.FIXED_INTRO) {
            return new SortPlan(IntroSortStrategy.ID, FeedMode.BULK, fallback, "fixed introsort");
        }
        if (policy == SelectionPolicy.STABLE) {
            return new SortPlan(MergeSortStrategy.ID, FeedMode.BULK, fallback, "stability requested");
        }

        int n = p.size();
        if (n <= 16) {
            return new SortPlan(InsertionSortStrategy.ID, FeedMode.BULK, fallback, "tiny input -> insertion");
        }

        double log2n = Math.log(n) / LN2;

        StrategyId bestId = IntroSortStrategy.ID;
        double bestCost = n * log2n;
        String why = "n log n introsort";

        long runs = Math.max(1L, Math.round((1.0 - p.sortednessRatio()) * (n - 1)) + 1);
        double timsortCost = TIMSORT_OVERHEAD * n * (Math.log(runs) / LN2 + 1.0); // +1 guards runs == 1
        if (timsortCost < bestCost) {
            bestId = JdkSortStrategy.ID;
            bestCost = timsortCost;
            why = "few runs (~" + runs + ") -> TimSort";
        }

        KeyStats ks = p.keyStats();
        if (ks != null) {
            long span = ks.span();
            if (ks.countingFeasible() && span >= 0) {
                double countingCost = (double) n + (span + 1);
                if (countingCost < bestCost) {
                    bestId = CountingSortStrategy.ID;
                    bestCost = countingCost;
                    why = "n+range counting (range " + (span + 1) + ")";
                }
            }
            double radixCost = RADIX_PASSES * n;
            if (radixCost < bestCost) {
                bestId = RadixSortStrategy.ID;
                bestCost = radixCost;
                why = "fixed-pass LSD radix";
            }
        }

        return new SortPlan(bestId, FeedMode.BULK, fallback, "cost model -> " + why);
    }
}
