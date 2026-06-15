package io.github.richeyworks.superbeefsort.select;

import io.github.richeyworks.superbeefsort.core.StrategyId;
import io.github.richeyworks.superbeefsort.feed.FeedMode;
import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.profile.KeyStats;
import io.github.richeyworks.superbeefsort.registry.StrategyRegistry;
import io.github.richeyworks.superbeefsort.strategy.CountingSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.IntroSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.JdkSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.MergeSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.RadixSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.SortingNetworkStrategy;

/**
 * Capability/heuristic selection. Phase 1 adds the non-comparison branch: when the profiler reports
 * faithful integer key stats, pick counting sort for a bounded range, otherwise LSD radix. Tiny inputs
 * use a fixed sorting-network kernel; nearly-sorted inputs prefer run-aware TimSort. An ML-backed selector (Phase 4) can replace
 * this behind {@link StrategySelector} with no caller changes. Every plan carries an introsort fallback.
 */
public final class RuleBasedStrategySelector implements StrategySelector {

    private static final long COUNTING_RANGE_FLOOR = 1L << 16;

    @Override
    public SortPlan select(DataProfile profile, SelectionPolicy policy, StrategyRegistry registry) {
        StrategyId fallback = IntroSortStrategy.ID;
        return switch (policy) {
            case FIXED_INTRO -> new SortPlan(IntroSortStrategy.ID, FeedMode.BULK, fallback, "fixed introsort");
            case STABLE -> new SortPlan(MergeSortStrategy.ID, FeedMode.BULK, fallback, "stability requested");
            case SMART -> smart(profile, fallback);
        };
    }

    private SortPlan smart(DataProfile p, StrategyId fallback) {
        if (p.tiny()) {
            return new SortPlan(SortingNetworkStrategy.ID, FeedMode.BULK, fallback, "tiny input -> sorting network");
        }
        if (p.nearlySorted()) {
            // Adjacency sortedness is high, but a few far-displaced elements can still mean many
            // total inversions, where plain insertion (O(n + inversions)) blows up. TimSort is
            // run-aware AND O(n log n) worst case, so it stays fast in both shapes.
            return new SortPlan(JdkSortStrategy.ID, FeedMode.BULK, fallback,
                    "nearly sorted (" + pct(p.sortednessRatio()) + ") -> run-aware TimSort");
        }
        KeyStats ks = p.keyStats();
        if (ks != null) {
            long span = ks.span();
            boolean counting = ks.countingFeasible() && span <= Math.max(COUNTING_RANGE_FLOOR, 4L * p.size());
            if (counting) {
                return new SortPlan(CountingSortStrategy.ID, FeedMode.BULK, fallback,
                        "bounded integer keys (range " + (span + 1) + ") -> counting sort");
            }
            return new SortPlan(RadixSortStrategy.ID, FeedMode.BULK, fallback,
                    "integer keys (~" + p.distinctEstimate() + " distinct) -> LSD radix");
        }
        return new SortPlan(IntroSortStrategy.ID, FeedMode.BULK, fallback, "general input -> introsort");
    }

    private static String pct(double ratio) {
        return Math.round(ratio * 100) + "%";
    }
}
