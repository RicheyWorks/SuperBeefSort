package io.github.richeyworks.superbeefsort.feed;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.control.WorkloadMonitor;
import io.github.richeyworks.csrbt.ensemble.EnsembleMode;
import io.github.richeyworks.csrbt.ensemble.EnsembleOrderedSet;
import io.github.richeyworks.csrbt.interfaces.OrderedCollection;
import io.github.richeyworks.csrbt.interfaces.SelfHealingTree;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * SuperBeefSort's boundary to CSRBT. Wraps any {@link OrderedCollection} — both {@link OrderedSet} and
 * {@link EnsembleOrderedSet} implement it — and exposes the comparator CSRBT orders by, the
 * {@link SelfHealingTree} health hook, the empty-{@link OrderedSet} O(n) {@code buildFromSorted} fast path,
 * and (Phase 3) the empty-mirror-ensemble {@code buildAllFromSorted} fan-out.
 *
 * <p>The comparator is captured here so the engine can guarantee SuperBeefSort sorts under the exact
 * order CSRBT inserts under; a mismatch would silently corrupt the tree.</p>
 *
 * <p><b>Threading (hardening L-3):</b> not thread-safe by design, matching CSRBT's feed cadence —
 * one thread drives one target. {@link #observedBy} and {@link #withHealthHook} set plain fields
 * with no synchronized publication, and the attached {@code WorkloadMonitor} is itself
 * single-threaded by contract. (The parallelism in a parallel feed lives <em>inside</em> CSRBT's
 * member fan-out and never touches this class concurrently.)</p>
 */
public final class CsrbtTarget<K> {

    private final OrderedCollection<K> collection;
    private final Comparator<? super K> comparator;       // may be null if unknown
    private final SelfHealingTree healing;                // may be null
    private final OrderedSet<K> orderedSet;               // non-null only when the target is an OrderedSet
    private final EnsembleOrderedSet<K> ensemble;         // non-null only when the target is an EnsembleOrderedSet

    private WorkloadMonitor monitor;                      // optional: feed traffic becomes control-plane signal
    private BooleanSupplier healthHook;                   // optional: overrides the SelfHealingTree hook

    private CsrbtTarget(OrderedCollection<K> collection, Comparator<? super K> comparator,
                        SelfHealingTree healing, OrderedSet<K> orderedSet, EnsembleOrderedSet<K> ensemble) {
        this.collection = collection;
        this.comparator = comparator;
        this.healing = healing;
        this.orderedSet = orderedSet;
        this.ensemble = ensemble;
    }

    public static <K> CsrbtTarget<K> of(OrderedSet<K> set) {
        return new CsrbtTarget<>(set, set.comparator(), set, set, null);
    }

    public static <K> CsrbtTarget<K> of(EnsembleOrderedSet<K> ensemble) {
        return new CsrbtTarget<>(ensemble, ensemble.comparator(), null, null, ensemble);
    }

    @SuppressWarnings("unchecked")
    public static <K> CsrbtTarget<K> of(OrderedCollection<K> collection, Comparator<? super K> comparator) {
        SelfHealingTree healing = (collection instanceof SelfHealingTree sh) ? sh : null;
        OrderedSet<K> orderedSet = (collection instanceof OrderedSet) ? (OrderedSet<K>) collection : null;
        EnsembleOrderedSet<K> ensemble = (collection instanceof EnsembleOrderedSet)
                ? (EnsembleOrderedSet<K>) collection : null;
        return new CsrbtTarget<>(collection, comparator, healing, orderedSet, ensemble);
    }

    /**
     * Route feed traffic into a CSRBT {@link WorkloadMonitor}, so the feed itself becomes visible to
     * the control plane instead of leaving a freshly adaptive tree with an EMPTY feature vector: every
     * effective insert through {@link #add} / {@link #bulkBuild} / {@link #ensembleBulkBuild} is
     * recorded as a write op. Pass e.g. {@code adaptation.monitor()} so the first
     * {@code maybeAdapt()}/{@code maybePromote()} after a feed scores real write-burst features.
     * {@code null} detaches. Returns {@code this} for chaining.
     */
    public CsrbtTarget<K> observedBy(WorkloadMonitor monitor) {
        this.monitor = monitor;
        return this;
    }

    /**
     * Install an explicit health hook consulted by {@link #checkHealth()} <em>instead of</em> the
     * {@link SelfHealingTree} seam. This is how an ensemble feed gets real health gating: an
     * {@code EnsembleOrderedSet} is not a {@code SelfHealingTree} (its richer story is the controller's
     * failover/quarantine/heal cadence), so pass {@code EnsembleAdaptation.healthHook()} and the
     * health-gated/precision feeders will exercise it mid-feed rather than reporting zero checks.
     * The hook returns {@code true} when the cadence found the target healthy. {@code null} detaches.
     */
    public CsrbtTarget<K> withHealthHook(BooleanSupplier hook) {
        this.healthHook = hook;
        return this;
    }

    public boolean add(K value) {
        boolean added = collection.add(value);
        if (added && monitor != null) {
            monitor.recordAdd(Objects.hashCode(value));
        }
        return added;
    }

    public int size() {
        return collection.size();
    }

    public Comparator<? super K> comparator() {
        return comparator;
    }

    public boolean supportsHealthCheck() {
        return healthHook != null || healing != null;
    }

    /** Asks CSRBT to validate and, if needed, repair itself. Returns true if the tree is valid. */
    public boolean checkHealth() {
        if (healthHook != null) {
            return healthHook.getAsBoolean();
        }
        return healing == null || healing.selfRepair();
    }

    /**
     * True when the target supports a bounded sliding window ({@code setMaxSize}): an
     * {@link OrderedSet}, or — since CSRBT's 2026-07-08 window seam — an {@link EnsembleOrderedSet}
     * whose members are all strategy-backed (an engine-tier member has no window, and CSRBT
     * refuses a half-windowed ensemble rather than letting it silently diverge).
     */
    public boolean supportsWindow() {
        return orderedSet != null || (ensemble != null && ensemble.supportsWindow());
    }

    /**
     * Bound the target to a sliding window of {@code n} (0 = unbounded); CSRBT FIFO-evicts
     * oldest-inserted keys — fanned uniformly across members for an ensemble target.
     */
    public void setMaxSize(int n) {
        if (orderedSet != null) {
            orderedSet.setMaxSize(n);
            return;
        }
        if (ensemble != null && ensemble.supportsWindow()) {
            ensemble.setMaxSize(n);
            return;
        }
        throw new IllegalStateException(
                "setMaxSize requires a windowed target: an OrderedSet, or an ensemble whose "
                + "members are all strategy-backed");
    }

    /** The target's current window capacity, or {@code 0} when unbounded / windowless. */
    public int getMaxSize() {
        if (orderedSet != null) {
            return orderedSet.getMaxSize();
        }
        return (ensemble != null && ensemble.supportsWindow()) ? ensemble.getMaxSize() : 0;
    }

    /** True when an O(n) bulk build is possible: the target is an {@link OrderedSet} and currently empty. */
    public boolean supportsBulkBuild() {
        return orderedSet != null && orderedSet.isEmpty();
    }

    /** Build the (empty) OrderedSet target in O(n) from an ascending, distinct run via CSRBT's fromSorted. */
    public void bulkBuild(List<K> ascendingDistinct) {
        if (orderedSet == null) {
            throw new IllegalStateException("bulk build requires an OrderedSet target");
        }
        orderedSet.buildFromSorted(ascendingDistinct);
        recordBulk(ascendingDistinct);
    }

    /** Fold a bulk-built run into the attached monitor: the load is a write burst the scorer should see. */
    private void recordBulk(List<K> keys) {
        if (monitor == null) {
            return;
        }
        for (K k : keys) {
            monitor.recordAdd(Objects.hashCode(k));
        }
    }

    /**
     * True when the target is an <em>empty</em> {@link EnsembleOrderedSet} in an all-exact mode
     * ({@link EnsembleMode#MIRROR}/{@link EnsembleMode#VERIFIED}): every mirror member can then be
     * bulk-built in O(n), fanned out across members by the ensemble's own executor.
     */
    public boolean supportsEnsembleBulkBuild() {
        return ensemble != null && ensemble.isEmpty()
                && (ensemble.mode() == EnsembleMode.MIRROR || ensemble.mode() == EnsembleMode.VERIFIED);
    }

    /** Build every ensemble member from the ascending, distinct run (O(n)/member, fanned out). */
    public void ensembleBulkBuild(List<K> ascendingDistinct) {
        if (ensemble == null) {
            throw new IllegalStateException("ensemble bulk build requires an EnsembleOrderedSet target");
        }
        ensemble.buildAllFromSorted(ascendingDistinct);
        recordBulk(ascendingDistinct);
    }
}
