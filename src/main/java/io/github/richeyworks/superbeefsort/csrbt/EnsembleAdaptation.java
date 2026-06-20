package io.github.richeyworks.superbeefsort.csrbt;

import io.github.richeyworks.csrbt.control.CostModelStrategyScorer;
import io.github.richeyworks.csrbt.control.MorphPolicy;
import io.github.richeyworks.csrbt.control.RollingWorkloadMonitor;
import io.github.richeyworks.csrbt.control.StrategyId;
import io.github.richeyworks.csrbt.control.StrategyScorer;
import io.github.richeyworks.csrbt.control.WorkloadMonitor;
import io.github.richeyworks.csrbt.ensemble.EnsembleController;
import io.github.richeyworks.csrbt.ensemble.EnsembleOrderedSet;
import io.github.richeyworks.superbeefsort.core.SortResult;
import io.github.richeyworks.superbeefsort.profile.DataProfile;

import java.util.Objects;

/**
 * Wires a built {@link EnsembleOrderedSet} to CSRBT's <em>ensemble</em> control plane, so the read path
 * keeps migrating to whichever member matches the live access pattern — the ensemble analog of
 * {@link WorkloadAdaptation}, closing the §4 "promotion" gap of docs/architecture-csrbt-integration.md.
 *
 * <p>Where {@link WorkloadAdaptation} drives a single {@code OrderedSet}'s {@link MorphPolicy}-gated
 * morph (an O(n) build-aside via {@code setStrategy}), this drives an {@link EnsembleController}: every
 * candidate strategy is already built and kept in sync as a member, so committing a decision is
 * {@code EnsembleOrderedSet.promote} — an <b>O(1) atomic primary swap, no rebuild</b>. That swap is the
 * payoff for the mirror's write fan-out.</p>
 *
 * <p>The controller is also the ensemble's data-plane facade, so route live operations through this
 * instance — {@link #add}, {@link #remove}, {@link #contains} — to both apply them and fold them into the
 * monitor (the read/write mix and hot-key skew the scorer reads). Then periodically call
 * {@link #maybePromote()} to let the policy gate a promotion, and {@link #checkHealth()} on a cadence to
 * fail a bad primary over to a healthy member (and quarantine/heal/retire the rest). {@link #report()}
 * summarizes what happened.</p>
 *
 * <p>No CSRBT changes: composes only the public {@link EnsembleController} +
 * {@link RollingWorkloadMonitor} / {@link CostModelStrategyScorer} around the public ensemble. A
 * {@link #attachProfileGuided} factory lets SuperBeefSort's data profile prime the promotion decision via
 * {@link ProfileGuidedScorer}, mirroring {@code buildCoOptimized} for the single-set case.</p>
 */
public final class EnsembleAdaptation<K> {

    private final EnsembleOrderedSet<K> ensemble;
    private final EnsembleController<K> controller;
    private int opsSinceEval;
    private int evaluations;
    private int promotions;
    private int failovers;

    private EnsembleAdaptation(EnsembleOrderedSet<K> ensemble, WorkloadMonitor monitor,
                              StrategyScorer scorer, MorphPolicy policy) {
        this.ensemble = Objects.requireNonNull(ensemble, "ensemble");
        Objects.requireNonNull(monitor, "monitor");
        Objects.requireNonNull(scorer, "scorer");
        Objects.requireNonNull(policy, "policy");
        this.controller = new EnsembleController<>(ensemble, monitor, scorer, policy);
    }

    /** Attach promotion backed by CSRBT's default rolling monitor + cost-model scorer under {@code policy}. */
    public static <K> EnsembleAdaptation<K> attach(EnsembleOrderedSet<K> ensemble, MorphPolicy policy) {
        return new EnsembleAdaptation<>(ensemble, new RollingWorkloadMonitor(),
                new CostModelStrategyScorer(), policy);
    }

    /** Attach with a caller-supplied monitor + scorer. */
    public static <K> EnsembleAdaptation<K> attach(EnsembleOrderedSet<K> ensemble, WorkloadMonitor monitor,
                                                   StrategyScorer scorer, MorphPolicy policy) {
        return new EnsembleAdaptation<>(ensemble, monitor, scorer, policy);
    }

    /**
     * Attach <em>profile-guided</em> promotion: CSRBT's cost-model scorer biased by a
     * {@link ProfileGuidedScorer} prior toward the strategy SuperBeefSort's {@code profile} + {@code access}
     * favor, so the read path starts migrating toward the right member before live traffic has fully spoken.
     */
    public static <K> EnsembleAdaptation<K> attachProfileGuided(EnsembleOrderedSet<K> ensemble,
                                                               DataProfile profile, AccessPolicy access,
                                                               MorphPolicy policy) {
        return attach(ensemble, new RollingWorkloadMonitor(), ProfileGuidedScorer.forProfile(profile, access), policy);
    }

    /**
     * As {@link #attachProfileGuided(EnsembleOrderedSet, DataProfile, AccessPolicy, MorphPolicy)} but with the
     * prior <em>strength</em> {@linkplain ProfileGuidedScorer#derivePrior derived from the realized sort run}
     * ({@code metrics}) rather than fixed at {@link ProfileGuidedScorer#DEFAULT_PRIOR} — the ensemble analog of
     * the Gap&nbsp;5 handoff: a clean, cheap, exactly-measured run nudges the read path harder toward the
     * favored member, an expensive/uncertain run more softly. A {@code null} {@code metrics} reproduces the
     * fixed-prior overload. The "two engines talking" wiring for promotion ({@code BeefSort.buildCoOptimizedEnsemble}).
     */
    public static <K> EnsembleAdaptation<K> attachProfileGuided(EnsembleOrderedSet<K> ensemble,
                                                               DataProfile profile, AccessPolicy access,
                                                               SortResult metrics, MorphPolicy policy) {
        return attach(ensemble, new RollingWorkloadMonitor(),
                ProfileGuidedScorer.forRun(profile, access, metrics), policy);
    }

    /** The live ensemble: route reads/writes through this adapter, which is the one promoted in place. */
    public EnsembleOrderedSet<K> ensemble() { return ensemble; }

    /** The {@link StrategyId} of the member currently serving reads, or {@code null} if unmapped. */
    public StrategyId currentPrimary() { return controller.currentPrimaryId(); }

    // -- data-plane facade: apply to the ensemble and feed the monitor --

    public boolean add(K key) {
        opsSinceEval++;
        return controller.add(key);
    }

    public boolean remove(K key) {
        opsSinceEval++;
        return controller.remove(key);
    }

    public boolean contains(K key) {
        opsSinceEval++;
        return controller.contains(key);
    }

    /**
     * Let CSRBT evaluate the live workload and promote the read path to the cheapest <em>available</em>
     * member if the policy gates clear (an O(1) primary swap). The "ops elapsed" handed to the policy is
     * the number of operations recorded through this adapter since the previous call.
     */
    public EnsembleController.PromotionResult maybePromote() {
        int elapsed = opsSinceEval;
        opsSinceEval = 0;
        EnsembleController.PromotionResult result = controller.evaluateAndMaybePromote(elapsed);
        evaluations++;
        if (result.promoted()) {
            promotions++;
        }
        return result;
    }

    /**
     * Run one health-check cadence: a primary that fails its structural invariants is failed over to a
     * healthy member (O(1) swap), and diverged/broken members are quarantined, healed from the primary, or
     * retired. Reads are always served by a known-good primary (failover precedes quarantine).
     */
    public EnsembleController.HealthReport checkHealth() {
        EnsembleController.HealthReport result = controller.checkHealth();
        if (result.failedOver()) {
            failovers++;
        }
        return result;
    }

    /** A running {@link EnsembleAdaptationReport} of post-feed promotion + failover activity. */
    public EnsembleAdaptationReport report() {
        return new EnsembleAdaptationReport(evaluations, promotions, failovers, currentPrimary());
    }
}
