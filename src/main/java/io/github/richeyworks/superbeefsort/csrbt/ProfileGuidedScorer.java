package io.github.richeyworks.superbeefsort.csrbt;

import io.github.richeyworks.csrbt.control.CostModelStrategyScorer;
import io.github.richeyworks.csrbt.control.StrategyId;
import io.github.richeyworks.csrbt.control.StrategyScorer;
import io.github.richeyworks.csrbt.control.WorkloadFeatures;
import io.github.richeyworks.superbeefsort.core.SortResult;
import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.profile.Distribution;
import io.github.richeyworks.superbeefsort.profile.ProfileDepth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The "two engines talking" seam (docs/architecture-csrbt-integration.md §5): a CSRBT
 * {@link StrategyScorer} that lets <em>SuperBeefSort's data profile</em> bias CSRBT's tree-strategy
 * decision. It decorates a base scorer (CSRBT's closed-form {@link CostModelStrategyScorer} by default)
 * with a <em>prior</em> — a multiplicative discount on the estimated cost of the strategy the sort's
 * profile favors for this data — then re-ranks. The sort already knows the data's shape from profiling;
 * this hands that knowledge to the tree so adaptation starts in the right place instead of having to
 * rediscover it from scratch.
 *
 * <p>The prior is deliberately a <em>nudge</em>, not an override: it discounts the favored strategy's
 * cost by {@code prior} (default {@value #DEFAULT_PRIOR}), so the live cost model still wins whenever
 * another strategy is cheaper by more than that margin. Early, when the {@link WorkloadFeatures} carry
 * little evidence and candidate costs are close, the prior decides; once real traffic separates the
 * costs, the data wins. This keeps a profile-advised, born-optimal tree (see {@link StrategyAdvisor})
 * from being morphed away prematurely before the workload has spoken, while never locking in a wrong
 * guess against clear evidence.</p>
 *
 * <p>Pure function over the immutable feature vector (the contract {@link StrategyScorer} relies on for
 * testability): the prior depends only on the profile fixed at construction, never on hidden state.
 * Composes public CSRBT control-plane types only — no CSRBT changes. Use via
 * {@link WorkloadAdaptation#attachProfileGuided} or {@code BeefSort.buildCoOptimized(...)}.</p>
 */
public final class ProfileGuidedScorer implements StrategyScorer {

    /** Default prior: discount the profile-favored strategy's cost by 15%. */
    public static final double DEFAULT_PRIOR = 0.15;

    /**
     * Lower bound for a prior <em>derived</em> from a realized run (see {@link #derivePrior}). The
     * {@code [MIN_PRIOR, MAX_PRIOR]} band is centered on {@link #DEFAULT_PRIOR} (their midpoint is exactly
     * {@code 0.15}), so a neutral run signal reproduces the historical fixed prior, a confident run
     * strengthens the nudge and an uncertain one weakens it.
     */
    public static final double MIN_PRIOR = 0.05;

    /** Upper bound for a derived prior; even here the prior stays a nudge, never an override of a clear winner. */
    public static final double MAX_PRIOR = 0.25;

    /** Weights of the two confidence facets in {@link #confidenceFrom} (sum to 1). */
    private static final double CLEANLINESS_WEIGHT = 0.5;
    private static final double CERTAINTY_WEIGHT = 0.5;

    private static final double LOG2 = Math.log(2.0);

    private final StrategyScorer base;
    private final StrategyId favored;
    private final double prior;

    /**
     * @param base    the scorer to bias (typically {@link CostModelStrategyScorer})
     * @param favored the morph-family strategy the data profile favors (see {@link #favoredStrategy})
     * @param prior   multiplicative cost discount applied to {@code favored}, in {@code [0,1)}
     */
    public ProfileGuidedScorer(StrategyScorer base, StrategyId favored, double prior) {
        this.base = Objects.requireNonNull(base, "base");
        this.favored = Objects.requireNonNull(favored, "favored");
        if (!(prior >= 0.0) || prior >= 1.0) {
            throw new IllegalArgumentException("prior must be in [0,1)");
        }
        this.prior = prior;
    }

    /** Decorate CSRBT's cost model with the default prior toward the profile-favored strategy. */
    public static ProfileGuidedScorer forProfile(DataProfile profile, AccessPolicy access) {
        return new ProfileGuidedScorer(new CostModelStrategyScorer(), favoredStrategy(profile, access), DEFAULT_PRIOR);
    }

    /** As {@link #forProfile(DataProfile, AccessPolicy)} but with a caller-supplied base scorer and prior. */
    public static ProfileGuidedScorer forProfile(DataProfile profile, AccessPolicy access,
                                                 StrategyScorer base, double prior) {
        return new ProfileGuidedScorer(base, favoredStrategy(profile, access), prior);
    }

    /**
     * Decorate CSRBT's cost model with a prior whose <em>strength</em> is {@linkplain #derivePrior derived
     * from the realized sort run} instead of fixed at {@link #DEFAULT_PRIOR}. This is the Gap&nbsp;5
     * "multi-objective handoff" (docs/adr-csrbt-integration-deepening.md): the sort already measured the data
     * while ordering it, so a run that came in clean and cheap — the profiler found real, exploitable
     * structure — and whose disorder signal was measured exactly hands the tree a <em>stronger</em> nudge
     * toward the favored strategy; an expensive, generic, or only-sampled run hands a weaker one. The favored
     * strategy itself is unchanged ({@link #favoredStrategy}); only the confidence placed in it moves.
     */
    public static ProfileGuidedScorer forRun(DataProfile profile, AccessPolicy access, SortResult metrics) {
        return new ProfileGuidedScorer(new CostModelStrategyScorer(),
                favoredStrategy(profile, access), derivePrior(metrics, profile));
    }

    public StrategyId favored() {
        return favored;
    }

    public double prior() {
        return prior;
    }

    @Override
    public List<Score> score(WorkloadFeatures features) {
        List<Score> baseScores = base.score(features);
        List<Score> out = new ArrayList<>(baseScores.size());
        for (Score s : baseScores) {
            if (s.strategy() == favored) {
                double discounted = s.estimatedCost() * (1.0 - prior);
                out.add(new Score(favored, discounted,
                        s.rationale() + " | profile-prior x" + String.format("%.2f", 1.0 - prior)
                                + " (favored by SBS data profile)"));
            } else {
                out.add(s);
            }
        }
        // Re-rank ascending (cheapest first); break ties deterministically by strategy ordinal, as the
        // StrategyScorer contract requires.
        out.sort(Comparator.comparingDouble(Score::estimatedCost)
                .thenComparingInt(s -> s.strategy().ordinal()));
        return out;
    }

    /**
     * Map the sort's {@link DataProfile} + the declared {@link AccessPolicy} to the CSRBT morph-family
     * strategy ({@code RED_BLACK}, {@code AVL}, {@code SPLAY}, {@code HYBRID}) the tree should prefer.
     * Mirrors {@link StrategyAdvisor} but stays within the morph-managed family so the result is always a
     * valid adaptation target (the static weight-balanced shape has no {@link StrategyId}): access pattern
     * is the primary driver, and for an unspecified (BALANCED) pattern the key {@link Distribution} breaks
     * the tie — clustered keys imply locality and prefer Splay.
     */
    public static StrategyId favoredStrategy(DataProfile profile, AccessPolicy access) {
        AccessPolicy a = (access == null) ? AccessPolicy.BALANCED : access;
        switch (a) {
            case SKEWED:
                return StrategyId.SPLAY;       // temporal locality: hot keys self-adjust toward the root
            case READ_HEAVY:
                return StrategyId.AVL;         // strictest balance -> shortest trees -> fastest lookups
            case WRITE_HEAVY:
                return StrategyId.RED_BLACK;   // fewest rotations/write within the morph family
            case BALANCED:
            default:
                if (profile != null && profile.distribution() == Distribution.CLUSTERED) {
                    return StrategyId.SPLAY;   // clustered keys imply access locality even if unstated
                }
                return StrategyId.RED_BLACK;   // general default (unchanged behaviour)
        }
    }

    /**
     * Derive a prior strength in {@code [}{@link #MIN_PRIOR}{@code , }{@link #MAX_PRIOR}{@code ]} from a
     * realized sort run. It is linear in a {@linkplain #confidenceFrom confidence} score in {@code [0,1]}:
     * a neutral run (confidence {@code 0.5}) reproduces {@link #DEFAULT_PRIOR} exactly (the band is centered
     * there), a fully-confident run yields {@link #MAX_PRIOR}, and a no-confidence run yields
     * {@link #MIN_PRIOR}. A {@code null} {@code metrics} (no run to learn from) falls back to
     * {@link #DEFAULT_PRIOR}, so callers without a realized run behave exactly as before.
     */
    public static double derivePrior(SortResult metrics, DataProfile profile) {
        if (metrics == null) {
            return DEFAULT_PRIOR;
        }
        double confidence = confidenceFrom(metrics, profile);
        return MIN_PRIOR + confidence * (MAX_PRIOR - MIN_PRIOR);
    }

    /**
     * Confidence in {@code [0,1]} that the profile-favored strategy is the right born strategy, read off the
     * realized run as two equally-weighted facets:
     * <ul>
     *   <li><b>cleanliness</b> — how far the sort's measured comparison count fell below the generic
     *       {@code n·log2 n} baseline. A cheap sort means the profiler found exploitable structure
     *       (near-sortedness, or integer keys admitting a ~0-comparison non-comparison sort), so its
     *       <em>other</em> structural read — the {@link Distribution} that picks the tree strategy — is
     *       trustworthy; a sort at or above baseline means generic data the profile could say little about.</li>
     *   <li><b>certainty</b> — whether the profile's global-disorder signal was measured exactly (DEEP / small
     *       inputs) or only sampled (large {@code SHALLOW} inputs), tempered for a shallow pass.</li>
     * </ul>
     */
    public static double confidenceFrom(SortResult metrics, DataProfile profile) {
        return clamp01(CLEANLINESS_WEIGHT * cleanliness(metrics) + CERTAINTY_WEIGHT * certainty(profile));
    }

    /** Cleanliness facet: 1 when the sort did ~0 comparisons, 0 once it reaches the {@code n·log2 n} baseline. */
    private static double cleanliness(SortResult metrics) {
        int n = metrics.size();
        if (n < 2) {
            return 0.5; // too small to read structure from — neutral
        }
        double baseline = n * (Math.log(n) / LOG2); // n·log2 n, the generic comparison-sort cost
        if (!(baseline > 0.0)) {
            return 0.5;
        }
        double ratio = metrics.comparisons() / baseline;
        return clamp01(1.0 - ratio);
    }

    /** Certainty facet: how exactly the profile measured the data's disorder (vs estimating it from a sample). */
    private static double certainty(DataProfile profile) {
        if (profile == null) {
            return 0.5; // no profile to judge — neutral
        }
        double c;
        if (profile.inversionsExact()) {
            c = 1.0;                 // exact global-disorder read (DEEP / small input)
        } else if (profile.inversionsMeasured()) {
            c = 0.6;                 // sampled estimate
        } else {
            c = 0.4;                 // disorder unmeasured
        }
        if (profile.depth() == ProfileDepth.SHALLOW) {
            c *= 0.9;                // a shallow pass observed less of the input
        }
        return clamp01(c);
    }

    private static double clamp01(double x) {
        return x < 0.0 ? 0.0 : (x > 1.0 ? 1.0 : x);
    }
}
