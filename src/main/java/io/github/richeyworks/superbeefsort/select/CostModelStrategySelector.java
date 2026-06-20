package io.github.richeyworks.superbeefsort.select;

import io.github.richeyworks.superbeefsort.core.StrategyCapabilities;
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
    // STABLE-policy crossover: above this, merge's O(n) reference scratch is prohibitive and the stable
    // O(1)-aux WikiSort is preferred. 16 MB == 2^21 (~2.1M) elements, reproducing the old size threshold —
    // derived from the strategies' declared StrategyCapabilities.AuxMemory rather than a magic number.
    // Distinct from smartAuxBudgetBytes below: this is a fixed merge->WikiSort crossover used only by STABLE.
    private static final long STABLE_WIKI_CROSSOVER_BYTES = 16L << 20; // 16 MB

    /**
     * Optional auxiliary-memory cap applied under SMART: any candidate whose declared
     * {@link StrategyCapabilities.AuxMemory#estimatedBytes(long)} exceeds this is excluded, so the selector
     * degrades to in-place sorts (introsort / insertion) under memory pressure. {@link Long#MAX_VALUE} means
     * unlimited — memory never excludes a candidate, which is the original behavior and the default.
     */
    private final long smartAuxBudgetBytes;

    /**
     * Optional <em>graded</em> auxiliary-memory penalty for the SMART objective: each candidate's estimated
     * cost is raised by {@code auxByteWeight ×} its declared
     * {@link StrategyCapabilities.AuxMemory#estimatedBytes(long)}, so memory-hungry sorts are discouraged in
     * proportion to their footprint rather than hard-excluded. {@code 0.0} (the default) adds nothing, so the
     * objective is byte-for-byte the original comparisons+moves estimate. This is the deterministic,
     * cost-model analog of {@link BanditStrategySelector#costWithMemory(double)} (which tunes on
     * <em>measured</em> aux) and a softer alternative to the {@link #smartAuxBudgetBytes} hard cap — the two
     * compose: a candidate is excluded if it exceeds the budget and otherwise penalized by its bytes.
     */
    private final double auxByteWeight;

    /** No budget and no penalty: memory never affects a SMART candidate (original behavior). */
    public CostModelStrategySelector() {
        this(Long.MAX_VALUE, 0.0);
    }

    /**
     * @param smartAuxBudgetBytes max auxiliary memory (bytes) a SMART candidate may use; strategies whose
     *     estimated peak aux exceeds it are excluded. Use {@link Long#MAX_VALUE} for unlimited.
     */
    public CostModelStrategySelector(long smartAuxBudgetBytes) {
        this(smartAuxBudgetBytes, 0.0);
    }

    /**
     * @param smartAuxBudgetBytes hard cap (bytes) on a SMART candidate's estimated aux; over-budget
     *     candidates are excluded. {@link Long#MAX_VALUE} for unlimited.
     * @param auxByteWeight graded penalty added to each SMART candidate's cost per estimated auxiliary byte
     *     ({@code >= 0}; {@code 0} disables it). The budget and the penalty compose.
     */
    public CostModelStrategySelector(long smartAuxBudgetBytes, double auxByteWeight) {
        if (smartAuxBudgetBytes <= 0) {
            throw new IllegalArgumentException("smartAuxBudgetBytes must be positive: " + smartAuxBudgetBytes);
        }
        if (!(auxByteWeight >= 0.0)) { // also rejects NaN
            throw new IllegalArgumentException("auxByteWeight must be >= 0: " + auxByteWeight);
        }
        this.smartAuxBudgetBytes = smartAuxBudgetBytes;
        this.auxByteWeight = auxByteWeight;
    }

    /**
     * A selector with a graded SMART aux-memory penalty and no hard budget — the deterministic, single-knob
     * way to make the cost model trade speed against memory (per estimated auxiliary byte) without the bandit.
     *
     * @param auxByteWeight cost added per estimated auxiliary byte ({@code >= 0})
     */
    public static CostModelStrategySelector withAuxPenalty(double auxByteWeight) {
        return new CostModelStrategySelector(Long.MAX_VALUE, auxByteWeight);
    }

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
        double bestCost = penalizedCost(IntroSortStrategy.ID, n * log2n, n, registry);
        String why = "n log n introsort";

        long runs = Math.max(1L, Math.round((1.0 - p.sortednessRatio()) * (n - 1)) + 1);
        double timsortCost = penalizedCost(JdkSortStrategy.ID,
                TIMSORT_OVERHEAD * n * (Math.log(runs) / LN2 + 1.0), n, registry); // +1 guards runs == 1
        if (timsortCost < bestCost && withinSmartBudget(JdkSortStrategy.ID, n, registry)) {
            bestId = JdkSortStrategy.ID;
            bestCost = timsortCost;
            why = "few runs (~" + runs + ") -> TimSort";
        }

        // Insertion sort's real cost is its adaptive O(n + inversions). Only trusted when the inversion
        // count is EXACT (small/DEEP inputs); an estimate is never used to pick an O(n^2)-risk strategy.
        if (p.inversionsExact() && p.inversions() >= 0) {
            double insertionCost = penalizedCost(InsertionSortStrategy.ID, (double) n + p.inversions(), n, registry);
            if (insertionCost < bestCost && withinSmartBudget(InsertionSortStrategy.ID, n, registry)) {
                bestId = InsertionSortStrategy.ID;
                bestCost = insertionCost;
                why = "n+inversions insertion (" + p.inversions() + " inv)";
            }
        }

        if (p.hasByteSequenceKey()) {
            double msdCost = penalizedCost(MsdRadixSortStrategy.ID, 8.0 * n, n, registry); // ~8 byte passes
            if (msdCost < bestCost && withinSmartBudget(MsdRadixSortStrategy.ID, n, registry)) {
                bestId = MsdRadixSortStrategy.ID;
                bestCost = msdCost;
                why = "byte-sequence keys -> MSD radix";
            }
        }

        KeyStats ks = p.keyStats();
        if (ks != null) {
            long span = ks.span();
            if (ks.countingFeasible() && span >= 0) {
                double countingCost = penalizedCost(CountingSortStrategy.ID, (double) n + (span + 1), n, registry);
                if (countingCost < bestCost && withinSmartBudget(CountingSortStrategy.ID, n, registry)) {
                    bestId = CountingSortStrategy.ID;
                    bestCost = countingCost;
                    why = "n+range counting (range " + (span + 1) + ")";
                }
            }
            double radixCost = penalizedCost(RadixSortStrategy.ID, RADIX_PASSES * n, n, registry);
            if (radixCost < bestCost && withinSmartBudget(RadixSortStrategy.ID, n, registry)) {
                bestId = RadixSortStrategy.ID;
                bestCost = radixCost;
                why = "fixed-pass LSD radix";
            }
            // Learned (sample) sort: distribution-adaptive, near-linear, and unconstrained by key range —
            // so for wide-range integers it beats fixed-pass radix; bounded ranges still favour counting.
            double learnedCost = penalizedCost(LearnedSortStrategy.ID, LEARNED_PER_ITEM * n, n, registry);
            if (learnedCost < bestCost && withinSmartBudget(LearnedSortStrategy.ID, n, registry)) {
                bestId = LearnedSortStrategy.ID;
                bestCost = learnedCost;
                why = "learned bucket sort (~" + (int) LEARNED_PER_ITEM + "n, distribution-adaptive)";
            }
        }

        if (smartAuxBudgetBytes != Long.MAX_VALUE) {
            why += " [aux<=" + (smartAuxBudgetBytes >> 20) + "MB]";
        }
        if (auxByteWeight > 0.0) {
            why += " [aux-penalty " + auxByteWeight + "/byte]";
        }
        return new SortPlan(bestId, FeedMode.BULK, fallback, "cost model -> " + why);
    }

    /**
     * Whether a candidate's declared auxiliary memory fits the SMART budget. Short-circuits to {@code true}
     * for the default unlimited budget (no registry lookup), so default selection stays allocation-free and
     * behaviour-identical; unknown ids are never excluded on memory grounds.
     */
    private boolean withinSmartBudget(StrategyId id, int n, StrategyRegistry registry) {
        if (smartAuxBudgetBytes == Long.MAX_VALUE) {
            return true;
        }
        if (!registry.contains(id)) {
            return true;
        }
        return registry.get(id).capabilities().auxMemory().estimatedBytes(n) <= smartAuxBudgetBytes;
    }

    /**
     * The candidate's estimated cost raised by the graded aux penalty: {@code baseCost + auxByteWeight ×}
     * its declared {@link StrategyCapabilities.AuxMemory#estimatedBytes(long)}. Short-circuits to
     * {@code baseCost} when no penalty is configured (no registry lookup), so the default objective is
     * byte-for-byte unchanged and allocation-free; an unknown id is never penalized. Applies under SMART
     * only — STABLE keeps its own merge→WikiSort crossover.
     */
    private double penalizedCost(StrategyId id, double baseCost, int n, StrategyRegistry registry) {
        if (auxByteWeight == 0.0) {
            return baseCost;
        }
        if (!registry.contains(id)) {
            return baseCost;
        }
        long auxBytes = registry.get(id).capabilities().auxMemory().estimatedBytes(n);
        return baseCost + auxByteWeight * auxBytes;
    }

    /**
     * Stable policy: plain merge sort by default, but the in-place WikiSort for large, mostly-distinct
     * inputs — stable and O(n&nbsp;log&nbsp;n) with O(1) auxiliary memory, trading a higher comparison
     * constant to avoid merge's O(n) scratch at scale. Mirrors {@link RuleBasedStrategySelector}; the
     * cost model's comparisons+moves objective never picks WikiSort on its own (plain merge dominates it
     * on both), so it is offered only where stability is the requirement.
     */
    private SortPlan stable(DataProfile p, StrategyId fallback) {
        // Memory-budgeted crossover (mirrors RuleBasedStrategySelector): the cost model's comparisons+moves
        // objective never picks WikiSort on its own (plain merge dominates both), so WikiSort is offered
        // only when merge's LINEAR auxiliary memory exceeds the budget AND the data is mostly distinct.
        // A configured smartAuxBudgetBytes governs this crossover too, so one budget drives both SMART
        // filtering and the STABLE switch; with no budget set it falls back to the fixed 16 MB threshold.
        long crossover = (smartAuxBudgetBytes != Long.MAX_VALUE) ? smartAuxBudgetBytes : STABLE_WIKI_CROSSOVER_BYTES;
        long mergeAuxBytes = StrategyCapabilities.AuxMemory.LINEAR.estimatedBytes(p.size());
        boolean mostlyDistinct = p.distinctEstimate() >= (long) (0.9 * p.size());
        // >= (not >): merge needing exactly the budget is already prohibitive, reproducing the old
        // `size >= 2^21` threshold byte-for-byte (8 B * 2^21 == 16 MB) when no budget is configured.
        if (mergeAuxBytes >= crossover && mostlyDistinct) {
            return new SortPlan(WikiSortStrategy.ID, FeedMode.BULK, fallback,
                    "merge scratch ~" + (mergeAuxBytes >> 20) + "MB >= " + (crossover >> 20)
                            + "MB budget, mostly-distinct (" + p.size() + " elems) -> WikiSort (O(1) aux, O(n log n))");
        }
        return new SortPlan(MergeSortStrategy.ID, FeedMode.BULK, fallback, "stability requested -> merge sort");
    }
}
