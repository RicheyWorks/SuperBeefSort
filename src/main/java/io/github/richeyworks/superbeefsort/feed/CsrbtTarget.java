package io.github.richeyworks.superbeefsort.feed;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.ensemble.EnsembleOrderedSet;
import io.github.richeyworks.csrbt.interfaces.OrderedCollection;
import io.github.richeyworks.csrbt.interfaces.SelfHealingTree;

import java.util.Comparator;

/**
 * SuperBeefSort's boundary to CSRBT. Wraps any {@link OrderedCollection} — both {@link OrderedSet}
 * and {@link EnsembleOrderedSet} implement it — and, when available, exposes the comparator CSRBT
 * orders by and the {@link SelfHealingTree} hook the health-gated feeder uses.
 *
 * <p>The comparator is captured here so the engine can guarantee SuperBeefSort sorts under the
 * exact order CSRBT inserts under; a mismatch would silently corrupt the tree.</p>
 */
public final class CsrbtTarget<K> {

    private final OrderedCollection<K> collection;
    private final Comparator<? super K> comparator; // may be null if unknown
    private final SelfHealingTree healing;          // may be null

    private CsrbtTarget(OrderedCollection<K> collection, Comparator<? super K> comparator, SelfHealingTree healing) {
        this.collection = collection;
        this.comparator = comparator;
        this.healing = healing;
    }

    public static <K> CsrbtTarget<K> of(OrderedSet<K> set) {
        return new CsrbtTarget<>(set, set.comparator(), set);
    }

    public static <K> CsrbtTarget<K> of(EnsembleOrderedSet<K> ensemble) {
        return new CsrbtTarget<>(ensemble, ensemble.comparator(), null);
    }

    public static <K> CsrbtTarget<K> of(OrderedCollection<K> collection, Comparator<? super K> comparator) {
        SelfHealingTree healing = (collection instanceof SelfHealingTree sh) ? sh : null;
        return new CsrbtTarget<>(collection, comparator, healing);
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
}
