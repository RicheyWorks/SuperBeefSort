package io.github.richeyworks.superbeefsort.csrbt;

import io.github.richeyworks.csrbt.control.CostModelStrategyScorer;
import io.github.richeyworks.csrbt.control.StrategyId;
import io.github.richeyworks.csrbt.control.StrategyScorer;
import io.github.richeyworks.csrbt.control.WorkloadFeatures;
import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.profile.Distribution;

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
}
