package io.github.richeyworks.superbeefsort.csrbt;

import io.github.richeyworks.csrbt.ensemble.EnsembleOrderedSet;
import io.github.richeyworks.csrbt.strategy.AVLStrategy;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.csrbt.strategy.SplayStrategy;
import io.github.richeyworks.superbeefsort.profile.DataProfile;

import java.util.Comparator;

/**
 * Composes an {@link EnsembleOrderedSet} member mix from a {@link DataProfile} + {@link AccessPolicy},
 * shaped by an {@link EnsembleSpec} (docs/architecture-csrbt-integration.md §4). Uses only CSRBT's public
 * builder — no CSRBT changes. MIRROR mode + {@code parallelFanOut()} so SuperBeefSort can bulk-load every
 * member concurrently.
 *
 * <ul>
 *   <li>{@link EnsembleSpec.Mix#LEAN} (the default) — the access-advised primary + a RedBlack replica:
 *       fault-tolerance / read-scaling at minimal K× cost.</li>
 *   <li>{@link EnsembleSpec.Mix#ADAPTIVE} — the morph-family trio RedBlack + AVL + Splay, so an
 *       {@code EnsembleController} can promote the read path to whichever member matches live traffic.</li>
 *   <li>{@code spec.snapshot()} adds a persistent engine member (O(1) snapshots, wait-free reads).</li>
 * </ul>
 */
public final class EnsembleTargetFactory {

    private EnsembleTargetFactory() {
    }

    /** Today's default mix ({@link EnsembleSpec#lean()}): access-advised primary + RedBlack replica. */
    public static <K> EnsembleOrderedSet<K> forProfile(DataProfile profile, AccessPolicy policy,
                                                       Comparator<? super K> order) {
        return forProfile(profile, policy, order, EnsembleSpec.lean());
    }

    /** Compose the member mix from the profile + access pattern per {@code spec}. */
    public static <K> EnsembleOrderedSet<K> forProfile(DataProfile profile, AccessPolicy policy,
                                                       Comparator<? super K> order, EnsembleSpec spec) {
        EnsembleOrderedSet.Builder<K> b = EnsembleOrderedSet.<K>builder(order);
        switch (spec.mix()) {
            case ADAPTIVE -> b
                    .member(() -> new RedBlackStrategy<K>())   // primary: robust general default
                    .member(() -> new AVLStrategy<K>())         // read-heavy promotion target (strict balance)
                    .member(() -> new SplayStrategy<K>());      // skew promotion target (self-adjusting)
            case LEAN -> b
                    .member(() -> StrategyAdvisor.advise(profile, policy)) // access-advised primary
                    .member(() -> new RedBlackStrategy<K>());              // robust, differently-balanced replica
        }
        if (spec.snapshot()) {
            b.persistentMember();   // O(1) snapshots / wait-free reads; never auto-promoted
        }
        return b.parallelFanOut().build();
    }
}
