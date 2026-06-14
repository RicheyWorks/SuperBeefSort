package io.github.richeyworks.superbeefsort.feed;

import java.util.List;

/**
 * Balanced insertion in batches, asking CSRBT to validate/repair its own health between batches
 * (the "precision pill-robot" personality). If the target exposes no health hook (e.g. an ensemble),
 * it behaves like a balanced feed and reports zero checks.
 */
public final class HealthGatedFeeder<K> implements SortFeeder<K> {

    private final int batchSize;

    public HealthGatedFeeder() {
        this(1024);
    }

    public HealthGatedFeeder(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be >= 1");
        }
        this.batchSize = batchSize;
    }

    @Override
    public FeedResult feed(List<K> sortedRun, CsrbtTarget<K> target) {
        long start = System.nanoTime();
        int n = sortedRun.size();
        int inserted = 0;
        int checks = 0;
        boolean healthy = true;
        for (int from = 0; from < n; from += batchSize) {
            int to = Math.min(n, from + batchSize);
            inserted += BalancedBuildFeeder.insertBalanced(sortedRun.subList(from, to), target);
            if (target.supportsHealthCheck()) {
                checks++;
                healthy = target.checkHealth();
            }
        }
        long elapsed = System.nanoTime() - start;
        int duplicates = n - inserted;
        return new FeedResult(FeedMode.HEALTH_GATED, n, inserted, duplicates, checks, healthy, elapsed);
    }

    @Override
    public FeedMode mode() {
        return FeedMode.HEALTH_GATED;
    }
}
