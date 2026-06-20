package io.github.richeyworks.superbeefsort.csrbt;

import io.github.richeyworks.csrbt.ensemble.EnsembleOrderedSet;
import io.github.richeyworks.csrbt.interfaces.RankedSet;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A small, uniform order-statistics view over a fed CSRBT target — the payoff of feeding an <em>ordered</em>
 * structure (docs/architecture-csrbt-integration.md §0). A {@code buildOrderedSet()} result already exposes
 * these directly (it is a {@link RankedSet}); the value this adds is the <em>ensemble</em> case:
 * {@code EnsembleOrderedSet} implements only {@code OrderedCollection}, so its order statistics are otherwise
 * reachable only by hand through its current primary member. {@link #ofEnsemble} re-resolves the primary on
 * every call, so the view keeps answering correctly across an O(1) read-path promotion.
 *
 * <p>Pure delegation — no new algorithms, no copy. All positional arguments are 1-indexed and all "or null
 * on empty" / out-of-range semantics are CSRBT's (see {@link RankedSet}).</p>
 */
public final class OrderStats<K> {

    private final Supplier<RankedSet<K>> source;

    private OrderStats(Supplier<RankedSet<K>> source) {
        this.source = source;
    }

    /** A view over any {@link RankedSet} — e.g. the {@code OrderedSet} returned by {@code buildOrderedSet()}. */
    public static <K> OrderStats<K> of(RankedSet<K> set) {
        Objects.requireNonNull(set, "set");
        return new OrderStats<>(() -> set);
    }

    /**
     * A view over an {@link EnsembleOrderedSet}, served by its <em>current</em> primary member — re-resolved
     * on each call, so the view follows a promotion. This is how SuperBeefSort surfaces order statistics from
     * a fed ensemble, which itself exposes only the {@code OrderedCollection} basics.
     */
    public static <K> OrderStats<K> ofEnsemble(EnsembleOrderedSet<K> ensemble) {
        Objects.requireNonNull(ensemble, "ensemble");
        return new OrderStats<>(() -> ensemble.primary().set());
    }

    /** ith smallest key (1-indexed). */
    public K select(int rank) {
        return source.get().select(rank);
    }

    /** 1-indexed rank of a present key. */
    public int rank(K value) {
        return source.get().rank(value);
    }

    /** Smallest key strictly greater than {@code value}, or {@code null} if none. */
    public K successor(K value) {
        return source.get().successor(value);
    }

    /** Largest key strictly less than {@code value}, or {@code null} if none. */
    public K predecessor(K value) {
        return source.get().predecessor(value);
    }

    /** Smallest key, or {@code null} if empty. */
    public K minimum() {
        return source.get().minimum();
    }

    /** Largest key, or {@code null} if empty. */
    public K maximum() {
        return source.get().maximum();
    }

    /** Lower median, or {@code null} if empty. */
    public K median() {
        return source.get().median();
    }

    /** kth-percentile key (0–100), or {@code null} if empty. */
    public K percentile(int pct) {
        return source.get().percentile(pct);
    }

    /** Count of keys in the closed range [lo, hi]. */
    public int countInRange(K lo, K hi) {
        return source.get().countInRange(lo, hi);
    }

    /** Keys in [lo, hi], ascending. */
    public List<K> rangeQuery(K lo, K hi) {
        return source.get().rangeQuery(lo, hi);
    }

    /** Number of keys. */
    public int size() {
        return source.get().size();
    }

    /** Whether the set is empty. */
    public boolean isEmpty() {
        return source.get().isEmpty();
    }
}
