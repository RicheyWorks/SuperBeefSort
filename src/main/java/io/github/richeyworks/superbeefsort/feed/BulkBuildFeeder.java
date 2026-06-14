package io.github.richeyworks.superbeefsort.feed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Prefers CSRBT's O(n) {@code buildFromSorted} fast path: when the target is an empty OrderedSet,
 * it de-duplicates the sorted run (linear, since it's already sorted) and builds the whole balanced
 * red-black tree in one shot instead of n median-first inserts. Falls back to
 * {@link BalancedBuildFeeder} for non-empty or non-OrderedSet targets (e.g. ensembles).
 */
public final class BulkBuildFeeder<K> implements SortFeeder<K> {

    @Override
    public FeedResult feed(List<K> sortedRun, CsrbtTarget<K> target) {
        if (!target.supportsBulkBuild()) {
            return new BalancedBuildFeeder<K>().feed(sortedRun, target); // reports its own BALANCED mode
        }
        long start = System.nanoTime();
        List<K> distinct = dedupSorted(sortedRun, target.comparator());
        target.bulkBuild(distinct);
        long elapsed = System.nanoTime() - start;
        return new FeedResult(FeedMode.BULK, sortedRun.size(), distinct.size(),
                sortedRun.size() - distinct.size(), 0, true, elapsed);
    }

    /** Drop consecutive equal keys from an already-sorted run. */
    static <K> List<K> dedupSorted(List<K> sorted, Comparator<? super K> comparator) {
        List<K> out = new ArrayList<>(sorted.size());
        for (K k : sorted) {
            if (out.isEmpty() || comparator.compare(out.get(out.size() - 1), k) != 0) {
                out.add(k);
            }
        }
        return out;
    }

    @Override
    public FeedMode mode() {
        return FeedMode.BULK;
    }
}
