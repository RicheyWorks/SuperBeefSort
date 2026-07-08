package io.github.richeyworks.superbeefsort.csrbt;

import io.github.richeyworks.csrbt.BPlusTreeEngine;
import io.github.richeyworks.csrbt.ensemble.EnsembleMode;
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

    /**
     * Above this profiled input size the factory adds a page-structured B+tree engine member (ADR-008,
     * CSRBT's large-n specialist) unless {@code spec.largeNEngine()} is off. 2²¹ ≈ 2M — the same
     * order-of-magnitude threshold SuperBeefSort's own selector uses for its large-n specialist
     * (WikiSort), so the two engines agree on what "large" means.
     */
    public static final int LARGE_N_THRESHOLD = 1 << 21;

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
        if (spec.largeNEngine() && profile != null && profile.size() >= LARGE_N_THRESHOLD) {
            // Size-aware engine membership: the profiler knows n exactly, so a large run gets CSRBT's
            // page-structured large-n specialist as a member (never auto-promoted; explicit promotion
            // or failover reaches it). Through the RankedSet seam — feeders need no changes.
            b.engineMember(() -> new BPlusTreeEngine<K>(order), "BPlusTreeEngine");
        }
        // The previously-unreachable ensemble knobs (see EnsembleSpec): mode + stride + shadow cadence
        // + memory ceiling + member cap. Zero/MIRROR values mean "builder default" and are not applied.
        if (spec.mode() != EnsembleMode.MIRROR) {
            b.mode(spec.mode());
        }
        if (spec.verifyEvery() > 0) {
            b.verifyEvery(spec.verifyEvery());
        }
        if (spec.shadowSampleRate() > 0.0) {
            b.shadowSampleRate(spec.shadowSampleRate());
        }
        if (spec.rebuildEvery() > 0) {
            b.rebuildEvery(spec.rebuildEvery());
        }
        if (spec.memoryCeilingBytes() > 0) {
            b.memoryCeilingBytes(spec.memoryCeilingBytes());
        }
        if (spec.maxMembers() > 0) {
            b.maxMembers(spec.maxMembers());
        }
        return b.parallelFanOut().build();
    }

    /**
     * Host ensemble for the evolution machine (see {@link EvolutionAdaptation}): the access-advised
     * primary holds the throne while {@code slots} Red-Black laboratory members stand by for the
     * controllers to re-shape per trial genome.
     *
     * <p>{@code exactShadows=false} → MIRROR: every member exact, O(n)/member bulk-loadable — the
     * bandit-search host. {@code exactShadows=true} → SAMPLED_SHADOW at rate 1.0 (CSRBT's canonical
     * evolution setup: laboratories receive every write, so trials are exact, but reads never fan out
     * to them) — the (μ+λ) population host; note bulk build then falls back to median-first feeding,
     * which the feeders detect automatically.</p>
     */
    public static <K> EnsembleOrderedSet<K> evolutionHost(DataProfile profile, AccessPolicy policy,
                                                          Comparator<? super K> order, int slots,
                                                          boolean exactShadows) {
        if (slots < 1) {
            throw new IllegalArgumentException("evolution needs at least one laboratory slot: " + slots);
        }
        EnsembleOrderedSet.Builder<K> b = EnsembleOrderedSet.<K>builder(order)
                .member(() -> StrategyAdvisor.advise(profile, policy));   // the throne
        for (int i = 0; i < slots; i++) {
            b.member(() -> new RedBlackStrategy<K>());                    // laboratory bodies
        }
        if (exactShadows) {
            b.mode(EnsembleMode.SAMPLED_SHADOW).shadowSampleRate(1.0);
        }
        return b.parallelFanOut().build();
    }
}
