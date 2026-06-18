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
import io.github.richeyworks.superbeefsort.strategy.LearnedSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.MergeSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.MsdRadixSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.RadixSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.WikiSortStrategy;

/**
 * Selects by estimating each applicable strategy's cost from the {@link DataProfile} and choosing the
 * cheapest, rather than applying fixed thresholds. The estimates are deliberately coarse — relative
 * order is what matters:
 *
 * <ul>
 *   <li>introsort (robust comparison sort): {@code n log2 n}</li>
 *   <li>run-aware TimSort: {@code 1.3 · n · log2(runs)}, with runs inferred from sortedness</li>
 *   <li>insertion (adaptive): {@code n + inversions}, considered only when the inversion count is exact</li>
 *   <li>counting (needs a faithful integer key in a bounded range): {@code n + range}</li>
 *   <li>LSD radix (needs a faithful integer key): {@code ~8 · n} (fixed byte passes)</li>
 *   <li>learned bucket sort (needs a faithful integer key, any range): {@code ~5 · n} (near-linear)</li>
 * </ul>
 *
 * <p>This naturally reproduces the rule-based choices and improves on them where they diverge — e.g.
 * for nearly-sorted <em>integer</em> data it picks linear counting (~2n) over TimSort (~14n). Pluggable
 * behind {@link StrategySelector}; the engine default remains {@link RuleBasedStrategySelector}.</p>
 */
public final class CostModelStrategySelector implements StrategySelector {

    private static final double LN2 = Math.log(2);
    private static final double RADIX_PASSES = 8.0;       // signed 64-bit keys -> ~8 byte passes
    private static final double LEARNED_PER_ITEM = 5.0;   // learned bucket sort: ~linear when buckets balance
    private static final double TIMSORT_OVERHEAD = 1.3;   // merge buffer allocations vs in-place sorts
    private static final int WIKI_MIN_SIZE = 1 << 21;     // 2_097_152: only here does avoiding merge's ~16MB+ O(n) scratch beat its ~2-3x wall-clock edge

    @Override
    public SortPlan select(DataProfile p, SelectionPolicy policy, StrategyRegistry registry) {
        StrategyId fallback = IntroSortStrategy.ID;
        if (policy == SelectionPolicy.FIXED_INTRO) {
            return new SortPlan(IntroSortStrategy.ID, FeedMode.BULK, fallback, "fixed introsort");
        }
        if (policy == SelectionPolicy.STABLE) {
            return stable(p, fallback);
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

        // Insertion sort's real cost is its adaptive O(n + inversions). Only trusted when the inversion
        // count is EXACT (small/DEEP inputs); an estimate is never used to pick an O(n^2)-risk strategy.
        if (p.inversionsExact() && p.inversions() >= 0) {
            double insertionCost = (double) n + p.inversions();
            if (insertionCost < bestCost) {
                bestId = InsertionSortStrategy.ID;
                bestCost = insertionCost;
                why = "n+inversions insertion (" + p.inversions() + " inv)";
            }
        }

        if (p.hasByteSequenceKey()) {
            double msdCost = 8.0 * n; // ~8 byte passes, distribution-adaptive
            if (msdCost < bestCost) {
                bestId = MsdRadixSortStrategy.ID;
                bestCost = msdCost;
                why = "byte-sequence keys -> MSD radix";
            }
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
            // Learned (sample) sort: distribution-adaptive, near-linear, and unconstrained by key range —
            // so for wide-range integers it beats fixed-pass radix; bounded ranges still favour counting.
            double learnedCost = LEARNED_PER_ITEM * n;
            if (learnedCost < bestCost) {
                bestId = LearnedSortStrategy.ID;
                bestCost = learnedCost;
                why = "learned bucket sort (~" + (int) LEARNED_PER_ITEM + "n, distribution-adaptive)";
            }
        }

        return new SortPlan(bestId, FeedMode.BULK, fallback, "cost model -> " + why);
    }

    /**
     * Stable policy: plain merge sort by default, but the in-place WikiSort for large, mostly-distinct
     * inputs — stable and O(n&nbsp;log&nbsp;n) with O(1) auxiliary memory, trading a higher comparison
     * constant to avoid merge's O(n) scratch at scale. Mirrors {@link RuleBasedStrategySelector}; the
     * cost model's comparisons+moves objective never picks WikiSort on its own (plain merge dominates it
     * on both), so it is offered only where stability is the requirement.
     */
    private SortPlan stable(DataProfile p, StrategyId fallback) {
        if (p.size() >= WIKI_MIN_SIZE && p.distinctEstimate() >= (long) (0.9 * p.size())) {
            return new SortPlan(WikiSortStrategy.ID, FeedMode.BULK, fallback,
                    "large mostly-distinct stable (" + p.size() + " elems) -> WikiSort (O(1) aux, O(n log n))");
        }
        return new SortPlan(MergeSortStrategy.ID, FeedMode.BULK, fallback, "stability requested -> merge sort");
    }
}
