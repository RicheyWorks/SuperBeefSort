package io.github.richeyworks.superbeefsort.feed;

import java.util.List;

/** Inserts the sorted run sequentially. Simplest feeder; leaves balancing entirely to CSRBT. */
public final class DirectFeeder<K> implements SortFeeder<K> {

    @Override
    public FeedResult feed(List<K> sortedRun, CsrbtTarget<K> target) {
        long start = System.nanoTime();
        int inserted = 0;
        for (K k : sortedRun) {
            if (target.add(k)) {
                inserted++;
            }
        }
        long elapsed = System.nanoTime() - start;
        int duplicates = sortedRun.size() - inserted;
        return new FeedResult(FeedMode.DIRECT, sortedRun.size(), inserted, duplicates, 0, true, elapsed);
    }

    @Override
    public FeedMode mode() {
        return FeedMode.DIRECT;
    }
}
