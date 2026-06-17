package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.csrbt.AccessPolicy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collector;

/**
 * {@link Collector}s that make SuperBeefSort a first-class sink for the Java Streams API — the
 * "productization" entry point (docs/IDEAS.md). Each collector gathers the stream's elements, then runs the
 * full {@code profile -> select -> sort -> (build)} pipeline once in its finisher, so a stream collapses
 * straight into a sorted {@link List} or a born-optimal CSRBT {@link OrderedSet}:
 *
 * <pre>{@code
 * OrderedSet<Integer> top = ids.stream()
 *         .collect(BeefCollectors.toOrderedSet(Comparator.naturalOrder(), KeyEncoder.ofInt(i -> i)));
 * }</pre>
 *
 * <p>These are ordinary (non-concurrent) collectors: under a parallel stream the elements are accumulated
 * per-thread into lists that the combiner merges in encounter order, and the single finisher does the sort,
 * so the result is correct and the sort stays stable — the parallelism is in the stream's element production,
 * not in CSRBT (which is fed once, single-threaded). Supplying a {@link KeyEncoder} unlocks the linear-time
 * counting / radix path exactly as on {@link BeefSort}; omitting it stays on comparison sorts.</p>
 */
public final class BeefCollectors {

    private BeefCollectors() {
    }

    /** Collect into a sorted {@link List} (duplicates retained) via the engine's chosen strategy. */
    public static <K> Collector<K, ?, List<K>> toSortedList(Comparator<? super K> comparator) {
        return toSortedList(comparator, null);
    }

    /** As {@link #toSortedList(Comparator)} but with a {@link KeyEncoder} to enable non-comparison sorts. */
    public static <K> Collector<K, ?, List<K>> toSortedList(Comparator<? super K> comparator, KeyEncoder<K> encoder) {
        return Collector.<K, ArrayList<K>, List<K>>of(
                ArrayList::new,
                List::add,
                BeefCollectors::merge,
                list -> BeefSort.<K>with(comparator).source(list).keyEncoder(encoder).run().sorted());
    }

    /**
     * Collect into a CSRBT {@link OrderedSet} built born-optimal in O(n) (via {@code BeefSort.buildOrderedSet};
     * the run is de-duplicated, so this is a set). Default {@link AccessPolicy#BALANCED} (Red-Black).
     */
    public static <K> Collector<K, ?, OrderedSet<K>> toOrderedSet(Comparator<? super K> comparator) {
        return toOrderedSet(comparator, null, AccessPolicy.BALANCED);
    }

    /** As {@link #toOrderedSet(Comparator)} but with a {@link KeyEncoder} for the non-comparison path. */
    public static <K> Collector<K, ?, OrderedSet<K>> toOrderedSet(Comparator<? super K> comparator, KeyEncoder<K> encoder) {
        return toOrderedSet(comparator, encoder, AccessPolicy.BALANCED);
    }

    /**
     * As {@link #toOrderedSet(Comparator, KeyEncoder)} but declaring the expected {@link AccessPolicy}, so the
     * tree is born in the access-advised balancing strategy ({@code READ_HEAVY -> AVL}, {@code SKEWED -> Splay},
     * {@code WRITE_HEAVY -> weight-balanced}, else Red-Black).
     */
    public static <K> Collector<K, ?, OrderedSet<K>> toOrderedSet(Comparator<? super K> comparator,
                                                                  KeyEncoder<K> encoder, AccessPolicy access) {
        return Collector.<K, ArrayList<K>, OrderedSet<K>>of(
                ArrayList::new,
                List::add,
                BeefCollectors::merge,
                list -> BeefSort.<K>with(comparator).source(list).keyEncoder(encoder)
                        .accessPattern(access).buildOrderedSet());
    }

    private static <K> ArrayList<K> merge(ArrayList<K> a, ArrayList<K> b) {
        a.addAll(b);
        return a;
    }
}
