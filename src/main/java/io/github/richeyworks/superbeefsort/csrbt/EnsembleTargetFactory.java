package io.github.richeyworks.superbeefsort.csrbt;

import io.github.richeyworks.csrbt.ensemble.EnsembleOrderedSet;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.superbeefsort.profile.DataProfile;

import java.util.Comparator;

/**
 * Composes an {@link EnsembleOrderedSet} member mix from a {@link DataProfile} + {@link AccessPolicy}
 * (docs/architecture-csrbt-integration.md §4). The <b>primary</b> is born with the access-advised strategy;
 * a RedBlack <b>replica</b> gives a differently-balanced second member for fault-tolerance / read-scaling
 * (CSRBT's promotion can later migrate the read path to whichever member matches the live workload). MIRROR
 * mode + {@code parallelFanOut()} so SuperBeefSort can bulk-load every member concurrently. Uses only CSRBT's
 * public builder — no CSRBT changes. (A persistent snapshot member is one extra {@code .persistentMember()}.)
 */
public final class EnsembleTargetFactory {

    private EnsembleTargetFactory() {
    }

    public static <K> EnsembleOrderedSet<K> forProfile(DataProfile profile, AccessPolicy policy,
                                                       Comparator<? super K> order) {
        return EnsembleOrderedSet.<K>builder(order)
                .member(() -> StrategyAdvisor.advise(profile, policy)) // primary: access-advised shape
                .member(() -> new RedBlackStrategy<K>())               // replica: robust, differently balanced
                .parallelFanOut()
                .build();
    }
}
