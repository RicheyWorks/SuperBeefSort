package io.github.richeyworks.superbeefsort.csrbt;

import io.github.richeyworks.csrbt.strategy.AVLStrategy;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.csrbt.strategy.SplayStrategy;
import io.github.richeyworks.csrbt.strategy.TreeStrategy;
import io.github.richeyworks.csrbt.strategy.WeightBalancedStrategy;
import io.github.richeyworks.superbeefsort.profile.DataProfile;

/**
 * Maps a {@link DataProfile} + a declared {@link AccessPolicy} to the CSRBT {@link TreeStrategy} a fed tree
 * should be <em>born</em> with, so SuperBeefSort can construct directly into the right shape via
 * {@code OrderedSet.fromSorted(run, strategy, order)} instead of inserting then morphing (see
 * docs/architecture-csrbt-integration.md §3). Pure function over CSRBT's public strategy constructors only.
 *
 * <p>{@code BALANCED} returns {@code RedBlackStrategy}, so the default behaviour is unchanged. The
 * {@code profile} argument is part of the contract (a seam for future distribution-aware refinement, e.g.
 * tuning the weight-balanced Δ from the key distribution); the v1 mapping is access-pattern-driven.</p>
 */
public final class StrategyAdvisor {

    private StrategyAdvisor() {
    }

    public static <K> TreeStrategy<K> advise(DataProfile profile, AccessPolicy policy) {
        AccessPolicy p = (policy == null) ? AccessPolicy.BALANCED : policy;
        switch (p) {
            case SKEWED:
                return new SplayStrategy<>();      // self-adjusting: hot keys migrate toward the root
            case READ_HEAVY:
                return new AVLStrategy<>();        // strictest balance -> shortest trees -> fastest lookups
            case WRITE_HEAVY:
                return new WeightBalancedStrategy<>(); // size-balanced: tolerates skew, rebalances less
            case BALANCED:
            default:
                return new RedBlackStrategy<>();   // general default: few rotations per write
        }
    }
}
