package io.github.richeyworks.superbeefsort.select;

import io.github.richeyworks.superbeefsort.core.SortResult;
import io.github.richeyworks.superbeefsort.core.StrategyId;
import io.github.richeyworks.superbeefsort.feed.FeedMode;
import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.profile.KeyStats;
import io.github.richeyworks.superbeefsort.registry.StrategyRegistry;
import io.github.richeyworks.superbeefsort.strategy.CountingSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.HeapSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.InsertionSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.IntroSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.JdkSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.LearnedSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.MergeSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.QuickSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.RadixSortStrategy;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;

/**
 * A self-tuning selector: a contextual multi-armed bandit that learns, per kind of input, which
 * strategy actually costs the least on this machine, and keeps the cost model honest by overriding
 * it where reality disagrees.
 *
 * <h2>How it works</h2>
 * <ul>
 *   <li><b>Context.</b> Each {@link DataProfile} is bucketed into a discrete context (size band x
 *       sortedness band x integer-keys? x distribution). Arms are learned independently per context,
 *       so "nearly-sorted integers, 50k" and "random comparables, 50M" don't pool their evidence.</li>
 *   <li><b>Arms.</b> The candidate strategies for a context, capability-gated: comparison sorts
 *       always; {@code insertion} only on small inputs (never explore O(n^2) on a big array);
 *       {@code counting}/{@code radix.lsd} only when faithful integer key stats are present (and
 *       counting only when the range is bounded). Mirrors the engine's own gating so a chosen arm
 *       always runs; no wasted pulls on fallbacks.</li>
 *   <li><b>Priors from the cost model.</b> Each arm starts at the analytical estimate
 *       ({@code n log n} / run-aware TimSort / {@code n+range} counting / fixed-pass radix), so the
 *       bandit's <em>first</em> guess equals the cost-model choice and it never has to physically try
 *       insertion-sort on a huge input to discover it is slow. Observation then refines the prior.</li>
 *   <li><b>Selection.</b> A UCB-style rule on <em>relative</em> cost:
 *       {@code score = mean/bestMean - c*sqrt(ln N / pulls)}, pick the minimum. Scale-free and
 *       deterministic; the exploration term shrinks as an arm is pulled, so it converges to the true
 *       argmin while still trying alternatives enough to escape a wrong prior.</li>
 * </ul>
 *
 * <p>Learning is opt-in via {@link LearningStrategySelector#observe}, which {@code BeefSortEngine}
 * calls after each sort. {@code FIXED_INTRO}/{@code STABLE} policies are deterministic, so those
 * delegate to a base selector ({@link RuleBasedStrategySelector} by default). Pluggable behind
 * {@link StrategySelector}; swap it in with {@code BeefSort.selector(...)} and reuse one instance
 * across jobs so the evidence accumulates.</p>
 */
public final class BanditStrategySelector implements LearningStrategySelector {

    private static final double LN2 = Math.log(2);
    private static final long COUNTING_RANGE_FLOOR = 1L << 16;

    private final double explore;
    private final ToDoubleFunction<SortResult> cost;
    private final StrategySelector base;
    private final Map<String, Map<String, Arm>> table = new ConcurrentHashMap<>();

    /** Defaults: exploration weight 0.7, cost = comparisons + moves, base = rule-based selector. */
    public BanditStrategySelector() {
        this(0.7, new RuleBasedStrategySelector(), r -> (double) (r.comparisons() + r.moves()));
    }

    /**
     * @param explore exploration weight (higher = explores longer; 0 = pure greedy on means)
     * @param base    selector used for non-SMART policies, which are deterministic
     * @param cost    maps a run's metrics to a scalar cost to minimize (e.g. comparisons + moves, or
     *                {@code SortResult::elapsedNanos} to tune on this machine's wall-clock timings)
     */
    public BanditStrategySelector(double explore, StrategySelector base, ToDoubleFunction<SortResult> cost) {
        this.explore = explore;
        this.base = base;
        this.cost = cost;
    }

    @Override
    public SortPlan select(DataProfile p, SelectionPolicy policy, StrategyRegistry registry) {
        if (policy != SelectionPolicy.SMART) {
            return base.select(p, policy, registry); // FIXED_INTRO / STABLE are not a learning problem
        }
        String ctx = contextKey(p);
        Set<StrategyId> arms = candidateArms(p, registry);
        Map<String, Arm> stats = table.computeIfAbsent(ctx, k -> new ConcurrentHashMap<>());
        for (StrategyId id : arms) {
            stats.computeIfAbsent(id.value(), k -> new Arm(priorCost(id, p)));
        }

        long n = 0;
        double bestMean = Double.MAX_VALUE;
        for (StrategyId id : arms) {
            Arm a = stats.get(id.value());
            n += a.pulls();
            bestMean = Math.min(bestMean, a.mean());
        }
        double lnN = Math.log(Math.max(2, n));
        double denom = bestMean + 1.0;

        StrategyId best = null;
        Arm bestArm = null;
        double bestScore = Double.MAX_VALUE;
        for (StrategyId id : arms) { // iterate candidate order for deterministic tie-breaks
            Arm a = stats.get(id.value());
            double relCost = (a.mean() + 1.0) / denom;
            double bonus = explore * Math.sqrt(lnN / a.pulls());
            double score = relCost - bonus;
            if (score < bestScore) {
                bestScore = score;
                best = id;
                bestArm = a;
            }
        }

        String why = String.format("bandit[%s] -> %s (mean~%.0f over %d pulls, %d arms)",
                ctx, best.value(), bestArm.mean(), bestArm.pulls(), arms.size());
        return new SortPlan(best, FeedMode.BULK, IntroSortStrategy.ID, why);
    }

    @Override
    public void observe(DataProfile p, StrategyId strategy, SortResult outcome) {
        if (strategy == null || outcome == null) {
            return;
        }
        Map<String, Arm> stats = table.computeIfAbsent(contextKey(p), k -> new ConcurrentHashMap<>());
        Arm a = stats.computeIfAbsent(strategy.value(), k -> new Arm(priorCost(strategy, p)));
        a.update(cost.applyAsDouble(outcome));
    }

    /** Observed mean cost for an arm in {@code profile}'s context, or {@code NaN} if never seen. */
    public double meanCost(DataProfile profile, StrategyId strategy) {
        Map<String, Arm> stats = table.get(contextKey(profile));
        if (stats == null) {
            return Double.NaN;
        }
        Arm a = stats.get(strategy.value());
        return a == null ? Double.NaN : a.mean();
    }

    /** Number of times an arm has been pulled (priors count as one), or 0 if never seen. */
    public long pulls(DataProfile profile, StrategyId strategy) {
        Map<String, Arm> stats = table.get(contextKey(profile));
        if (stats == null) {
            return 0;
        }
        Arm a = stats.get(strategy.value());
        return a == null ? 0 : a.pulls();
    }

    // -- context & arms --

    private static String contextKey(DataProfile p) {
        String size = p.size() <= 16 ? "tiny"
                : p.size() <= 1_024 ? "small"
                : p.size() <= 100_000 ? "medium" : "large";
        double s = p.sortednessRatio();
        String sort = s >= 0.90 ? "near" : s >= 0.60 ? "part" : "rand";
        String keys = p.keyStats() != null ? "int" : "cmp";
        // A global-disorder band so inputs that look alike by adjacency but differ in true inversion
        // count (e.g. a far-apart swap) learn separately; "i?" keeps unmeasured profiles in one bucket.
        String inv;
        if (!p.inversionsMeasured()) {
            inv = "i?";
        } else {
            double r = p.inversionRatio();
            inv = r < 0.01 ? "i0" : r < 0.10 ? "iLo" : r < 0.40 ? "iMid" : "iHi";
        }
        return size + "|" + sort + "|" + keys + "|" + p.distribution() + "|" + inv;
    }

    private static Set<StrategyId> candidateArms(DataProfile p, StrategyRegistry registry) {
        LinkedHashSet<StrategyId> arms = new LinkedHashSet<>();
        arms.add(IntroSortStrategy.ID);
        arms.add(QuickSortStrategy.ID);
        arms.add(JdkSortStrategy.ID);
        arms.add(MergeSortStrategy.ID);
        arms.add(HeapSortStrategy.ID);
        if (p.size() <= 1_024) {
            arms.add(InsertionSortStrategy.ID); // adaptive on small/nearly-sorted; never on big inputs
        }
        KeyStats ks = p.keyStats();
        if (ks != null) {
            arms.add(RadixSortStrategy.ID);
            arms.add(LearnedSortStrategy.ID);          // distribution-adaptive; any key range
            long span = ks.span();
            if (ks.countingFeasible() && span >= 0 && span <= Math.max(COUNTING_RANGE_FLOOR, 4L * p.size())) {
                arms.add(CountingSortStrategy.ID);
            }
        }
        arms.removeIf(id -> !registry.contains(id));
        if (arms.isEmpty()) {
            arms.add(IntroSortStrategy.ID);
        }
        return arms;
    }

    /**
     * Analytical prior cost (relative units), mirroring {@code CostModelStrategySelector}'s estimates.
     * Only the relative ordering matters; observation refines absolute values per machine.
     */
    private static double priorCost(StrategyId id, DataProfile p) {
        double n = Math.max(1, p.size());
        double log2n = Math.log(n) / LN2;
        double s = p.sortednessRatio();
        switch (id.value()) {
            case "counting": {
                KeyStats ks = p.keyStats();
                double range = ks != null ? (ks.span() + 1.0) : n;
                return n + Math.max(1.0, range);
            }
            case "radix.lsd":
                return 8.0 * n;                                  // ~8 fixed byte passes
            case "learned":
                return 5.0 * n;                                  // learned bucket sort: near-linear, range-agnostic
            case "insertion":
                // True adaptive cost is O(n + inversions). Use the exact count when the profiler measured
                // one (always the case at insertion's small candidate sizes); else a sortedness proxy.
                if (p.inversionsExact() && p.inversions() >= 0) {
                    return n + (double) p.inversions();
                }
                return n + (1.0 - s) * n * n * 0.25;             // proxy: cheap only when nearly sorted
            case "jdk.timsort": {
                double runs = Math.max(1.0, Math.round((1.0 - s) * (n - 1)) + 1);
                return 1.3 * n * (Math.log(runs) / LN2 + 1.0);   // run-aware
            }
            case "heap":
                return 1.6 * n * log2n;                          // n log n, cache-unfriendly constant
            case "merge":
                return 1.1 * n * log2n;                          // stable, aux buffer
            case "quick.threeway":
                return 1.0 * n * log2n;
            case "intro":
            default:
                return 1.05 * n * log2n;
        }
    }

    /** Running stats for one arm. The analytical prior seeds it as a single synthetic pull. */
    private static final class Arm {
        private long pulls;
        private double meanCost;

        Arm(double prior) {
            this.pulls = 1;
            this.meanCost = prior;
        }

        synchronized long pulls() {
            return pulls;
        }

        synchronized double mean() {
            return meanCost;
        }

        synchronized void update(double observedCost) {
            pulls++;
            meanCost += (observedCost - meanCost) / pulls; // incremental mean; prior decays as evidence grows
        }
    }
}
