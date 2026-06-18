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
import io.github.richeyworks.superbeefsort.strategy.MsdRadixSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.RadixSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.SortingNetworkStrategy;
import io.github.richeyworks.superbeefsort.strategy.WikiSortStrategy;

/**
 * Capability/heuristic selection. Phase 1 adds the non-comparison branch: when the profiler reports
 * faithful integer key stats, pick counting sort for a bounded range, otherwise LSD radix. Tiny inputs
 * use a fixed sorting-network kernel; inputs with a genuinely small (exactly measured) inversion count
 * go to adaptive insertion sort, and otherwise nearly-sorted inputs prefer run-aware TimSort. An
 * ML-backed selector (Phase 4) can replace this behind {@link StrategySelector} with no caller changes.
 * Every plan carries an introsort fallback.
 */
public final class RuleBasedStrategySelector implements StrategySelector {

    private static final long COUNTING_RANGE_FLOOR = 1L << 16;
    // WikiSort is ~2-3x slower than plain merge in wall-clock at every size (a higher comparison
    // constant), so it is only worth choosing when merge's O(n) scratch is genuinely prohibitive. At
    // 2^21 elements that scratch is ~16 MB+ of references; below this, plain merge is faster and the
    // memory saving is negligible. WikiSort here is purely the stable + O(1)-aux specialist for huge runs.
    private static final int WIKI_MIN_SIZE = 1 << 21; // 2_097_152

    @Override
    public SortPlan select(DataProfile profile, SelectionPolicy policy, StrategyRegistry registry) {
        StrategyId fallback = IntroSortStrategy.ID;
        return switch (policy) {
            case FIXED_INTRO -> new SortPlan(IntroSortStrategy.ID, FeedMode.BULK, fallback, "fixed introsort");
            case STABLE -> stable(profile, fallback);
            case SMART -> smart(profile, fallback);
        };
    }

    /**
     * Stable policy: plain merge sort by default (fast and stable). For very large, mostly-distinct inputs
     * prefer the in-place WikiSort instead — it keeps stability and O(n&nbsp;log&nbsp;n) work while dropping
     * merge's O(n) scratch to O(1), which matters at scale. WikiSort's block-merge fast path only engages on
     * distinct data; duplicate-heavy input falls back to a rotation merge with no win over plain merge, so
     * those stay on merge.
     */
    private SortPlan stable(DataProfile p, StrategyId fallback) {
        if (p.size() >= WIKI_MIN_SIZE && p.distinctEstimate() >= (long) (0.9 * p.size())) {
            return new SortPlan(WikiSortStrategy.ID, FeedMode.BULK, fallback,
                    "large mostly-distinct stable (" + p.size() + " elems, ~" + p.distinctEstimate()
                            + " distinct) -> WikiSort (stable, O(1) aux, O(n log n))");
        }
        return new SortPlan(MergeSortStrategy.ID, FeedMode.BULK, fallback, "stability requested -> merge sort");
    }

    private SortPlan smart(DataProfile p, StrategyId fallback) {
        if (p.tiny()) {
            return new SortPlan(SortingNetworkStrategy.ID, FeedMode.BULK, fallback, "tiny input -> sorting network");
        }
        if (p.hasByteSequenceKey()) {
            return new SortPlan(MsdRadixSortStrategy.ID, FeedMode.BULK, fallback, "byte-sequence keys -> MSD radix");
        }
        if (p.inversionsExact() && p.inversions() >= 0 && p.inversions() <= 2L * p.size()) {
            // Genuinely few inversions (a true global measure, not just adjacency): insertion sort is
            // adaptive O(n + inversions) and beats TimSort's merge-buffer overhead. Gated on an EXACT
            // count and a linear bound, so a sampling error can never route a high-inversion input into
            // O(n^2) -- and exact counts only exist for small/DEEP inputs, so a huge SHALLOW input with
            // high adjacency but distant disorder still falls through to the run-aware TimSort path.
            return new SortPlan(InsertionSortStrategy.ID, FeedMode.BULK, fallback,
                    "few inversions (" + p.inversions() + " <= 2n) -> insertion");
        }
        if (p.nearlySorted() || p.longestRunRatio() >= 0.5) {
            // Run-aware: high adjacency OR a single run covering half the input both favor TimSort,
            // whose run-merging exploits existing order and stays O(n log n) worst case. longestRun
            // catches "mostly one sorted block" inputs that the adjacency ratio alone misses.
            return new SortPlan(JdkSortStrategy.ID, FeedMode.BULK, fallback,
                    "run-aware (adjacency " + pct(p.sortednessRatio()) + ", longest run "
                            + pct(p.longestRunRatio()) + ") -> TimSort");
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
