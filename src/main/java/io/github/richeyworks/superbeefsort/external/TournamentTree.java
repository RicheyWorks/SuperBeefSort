package io.github.richeyworks.superbeefsort.external;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * k-way merge via a min-heap. Equal elements are ordered by ascending run index (lower index =
 * earlier chunk = earlier in the original input), so the merged output is stable with respect to
 * the run-generation order: elements from an earlier chunk precede later-chunk equals.
 */
final class TournamentTree<K> {

    // Generic record: V is the element type; independent of TournamentTree's K.
    private record Entry<V>(V value, int runIndex) {}

    private final PriorityQueue<Entry<K>> heap;
    private final List<SpillReader<K>> readers;

    TournamentTree(List<SpillReader<K>> readers, Comparator<? super K> comparator) {
        this.readers = readers;
        Comparator<Entry<K>> entryOrder = (a, b) -> {
            int c = comparator.compare(a.value(), b.value());
            return c != 0 ? c : Integer.compare(a.runIndex(), b.runIndex());
        };
        this.heap = new PriorityQueue<>(Math.max(1, readers.size()), entryOrder);
        for (int i = 0; i < readers.size(); i++) {
            SpillReader<K> r = readers.get(i);
            if (r.hasNext()) {
                heap.offer(new Entry<>(r.next(), i));
            }
        }
    }

    boolean hasNext() {
        return !heap.isEmpty();
    }

    K next() {
        Entry<K> e = heap.poll();
        SpillReader<K> r = readers.get(e.runIndex());
        if (r.hasNext()) {
            heap.offer(new Entry<>(r.next(), e.runIndex()));
        }
        return e.value();
    }

    void closeAll() {
        for (SpillReader<K> r : readers) {
            try {
                r.close();
            } catch (IOException ignored) {
            }
        }
    }
}
