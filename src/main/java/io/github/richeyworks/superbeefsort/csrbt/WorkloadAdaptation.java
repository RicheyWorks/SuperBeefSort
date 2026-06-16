package io.github.richeyworks.superbeefsort.csrbt;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.control.CostModelStrategyScorer;
import io.github.richeyworks.csrbt.control.MorphController;
import io.github.richeyworks.csrbt.control.MorphPolicy;
import io.github.richeyworks.csrbt.control.RollingWorkloadMonitor;
import io.github.richeyworks.csrbt.control.StrategyId;
import io.github.richeyworks.csrbt.control.StrategyScorer;
import io.github.richeyworks.csrbt.control.WorkloadMonitor;
import io.github.richeyworks.csrbt.strategy.AVLStrategy;
import io.github.richeyworks.csrbt.strategy.HybridStrategy;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.csrbt.strategy.SplayStrategy;
import io.github.richeyworks.csrbt.strategy.TreeStrategy;

import java.util.Objects;

/**
 * Wires a born-optimal {@link OrderedSet} to CSRBT's self-adaptive control plane, so its balancing
 * strategy keeps re-tuning to the <em>live</em> access pattern under an anti-thrash {@link MorphPolicy}
 * — the {@code .adaptWorkload(...)} rollout step of docs/architecture-csrbt-integration.md §5.
 *
 * <p>SuperBeefSort constructs the tree in the profile-advised shape ("born optimal", see
 * {@link StrategyAdvisor}); this adapter adds the feedback loop ("wired to adapt"). Report observed
 * operations against the live set — {@link #recordSearch}, {@link #recordAdd}, {@link #recordRemove}
 * (or the {@code observe*} key-typed conveniences) — then periodically call {@link #maybeAdapt()}.
 * Each call lets CSRBT's {@link MorphController} read the rolling workload, score the strategy family
 * with its cost model, and morph the tree in place <em>iff</em> a different strategy is cheaper by the
 * policy's margin, has held that lead for {@code stabilityWins} evaluations, and the cooldown has
 * elapsed. The morph itself is CSRBT's health-gated O(n) build-aside: a build that fails its health
 * check leaves the live tree untouched.
 *
 * <p>Adaptation is defined over CSRBT's morph-managed family — Red-Black, AVL, Splay, Hybrid (the
 * {@link StrategyId} domain). A set born {@code WeightBalanced} (the WRITE_HEAVY advice) is a
 * deliberately static, size-balanced target with no {@code StrategyId}; attaching adaptation to one
 * is rejected up front rather than silently migrating it out of the family.
 *
 * <p>No CSRBT changes: this composes only public control-plane types
 * ({@link RollingWorkloadMonitor}, {@link CostModelStrategyScorer}, {@link MorphController}) around
 * the public {@link OrderedSet}, which is itself the {@code StrategyMorphTarget} the controller drives.
 */
public final class WorkloadAdaptation<K> {

    private final OrderedSet<K> set;
    private final WorkloadMonitor monitor;
    private final MorphController<K> controller;
    private int opsSinceEval;

    private WorkloadAdaptation(OrderedSet<K> set, WorkloadMonitor monitor, StrategyScorer scorer,
                               MorphPolicy policy) {
        this.set = Objects.requireNonNull(set, "set");
        this.monitor = Objects.requireNonNull(monitor, "monitor");
        Objects.requireNonNull(scorer, "scorer");
        Objects.requireNonNull(policy, "policy");
        strategyIdOf(set.getStrategy()); // fail loud if the born strategy is outside the morph family
        this.controller = new MorphController<>(set, monitor, scorer, policy);
    }

    /** Attach adaptation backed by CSRBT's default rolling monitor + cost-model scorer under {@code policy}. */
    public static <K> WorkloadAdaptation<K> attach(OrderedSet<K> set, MorphPolicy policy) {
        return new WorkloadAdaptation<>(set, new RollingWorkloadMonitor(),
                new CostModelStrategyScorer(), policy);
    }

    /** Attach adaptation with a caller-supplied monitor + scorer (e.g. a tuned rolling window). */
    public static <K> WorkloadAdaptation<K> attach(OrderedSet<K> set, WorkloadMonitor monitor,
                                                   StrategyScorer scorer, MorphPolicy policy) {
        return new WorkloadAdaptation<>(set, monitor, scorer, policy);
    }

    /** The live set: reads/writes flow through this instance, which is the one morphed in place. */
    public OrderedSet<K> set() { return set; }

    /** The live set's current morph-managed strategy id (re-derived from {@code getStrategy()}). */
    public StrategyId currentStrategy() { return strategyIdOf(set.getStrategy()); }

    public void recordSearch(int keyHash, int depthTouched) {
        monitor.recordSearch(keyHash, depthTouched);
        opsSinceEval++;
    }

    public void recordAdd(int keyHash, int rotations) {
        monitor.recordAdd(keyHash, rotations);
        opsSinceEval++;
    }

    public void recordRemove(int keyHash, int rotations) {
        monitor.recordRemove(keyHash, rotations);
        opsSinceEval++;
    }

    public void recordSearch(int keyHash) { recordSearch(keyHash, 0); }

    public void recordAdd(int keyHash) { recordAdd(keyHash, 0); }

    public void recordRemove(int keyHash) { recordRemove(keyHash, 0); }

    /** Key-typed convenience: report a lookup of {@code key} (hashed for the workload sketch). */
    public void observeSearch(K key) { recordSearch(Objects.hashCode(key), 0); }

    /** Key-typed convenience: report an insertion of {@code key}. */
    public void observeAdd(K key) { recordAdd(Objects.hashCode(key), 0); }

    /** Key-typed convenience: report a removal of {@code key}. */
    public void observeRemove(K key) { recordRemove(Objects.hashCode(key), 0); }

    /**
     * Let CSRBT evaluate the live workload and morph the set's strategy if the policy gates clear.
     * The "ops elapsed" handed to the policy is the number of operations recorded since the previous
     * call, so the cooldown is measured in real traffic. Returns the controller's
     * {@link MorphController.MorphResult} (whether a morph happened, from/to, the health-gate outcome,
     * build nanos, and a human-readable reason).
     */
    public MorphController.MorphResult maybeAdapt() {
        int elapsed = opsSinceEval;
        opsSinceEval = 0;
        return controller.evaluateAndMaybeMorph(currentStrategy(), elapsed);
    }

    private static <K> StrategyId strategyIdOf(TreeStrategy<K> strategy) {
        if (strategy instanceof RedBlackStrategy) return StrategyId.RED_BLACK;
        if (strategy instanceof AVLStrategy)      return StrategyId.AVL;
        if (strategy instanceof SplayStrategy)    return StrategyId.SPLAY;
        if (strategy instanceof HybridStrategy)   return StrategyId.HYBRID;
        throw new IllegalArgumentException(
                "WorkloadAdaptation manages CSRBT's morph family (Red-Black, AVL, Splay, Hybrid); "
                        + strategy.getClass().getSimpleName()
                        + " has no StrategyId and is a static target. Use a BALANCED/READ_HEAVY/SKEWED "
                        + "access pattern (or an RB/AVL/Splay/Hybrid targetStrategy override) for adaptive builds.");
    }
}
