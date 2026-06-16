package io.github.richeyworks.superbeefsort.feed;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.ensemble.EnsembleMode;
import io.github.richeyworks.csrbt.ensemble.EnsembleOrderedSet;
import io.github.richeyworks.csrbt.interfaces.OrderedCollection;
import io.github.richeyworks.csrbt.interfaces.SelfHealingTree;

import java.util.Comparator;
import java.util.List;

/**
 * SuperBeefSort's boundary to CSRBT. Wraps any {@link OrderedCollection} — both {@link OrderedSet} and
 * {@link EnsembleOrderedSet} implement it — and exposes the comparator CSRBT orders by, the
 * {@link SelfHealingTree} health hook, the empty-{@link OrderedSet} O(n) {@code buildFromSorted} fast path,
 * and (Phase 3) the empty-mirror-ensemble {@code buildAllFromSorted} fan-out.
 *
 * <p>The comparator is captured here so the engine can guarantee SuperBeefSort sorts under the exact
 * order CSRBT inserts under; a mismatch would silently corrupt the tree.</p>
 */
public final class CsrbtTarget<K> {

    private final OrderedCollection<K> collection;
    private final Comparator<? super K> comparator;       // may be null if unknown
    private final SelfHealingTree healing;                // may be null
    private final OrderedSet<K> orderedSet;               // non-null only when the target is an OrderedSet
    private final EnsembleOrderedSet<K> ensemble;         // non-null only when the target is an EnsembleOrderedSet

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

    public boolean add(K value) {
        return collection.add(value);
    }

    public int size() {
        return collection.size();
    }

    public Comparator<? super K> comparator() {
        return comparator;
    }

    public boolean supportsHealthCheck() {
        return healing != null;
    }

    /** Asks CSRBT to validate and, if needed, repair itself. Returns true if the tree is valid. */
    public boolean checkHealth() {
        return healing == null || healing.selfRepair();
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
    }
}
