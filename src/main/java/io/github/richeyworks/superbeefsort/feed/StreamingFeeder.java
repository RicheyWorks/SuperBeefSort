package io.github.richeyworks.superbeefsort.feed;

import java.util.List;

/**
 * Streams a (sorted) run into a CSRBT target as a bounded sliding window: it sets the target's capacity
 * ({@code OrderedSet.setMaxSize}, FIFO eviction of the oldest-inserted key) and then feeds in batches with
 * health backpressure between them, per a {@link HealthPolicy}. Because the run arrives ascending and CSRBT
 * evicts the oldest-inserted (smallest) key once the bound is reached, a capped feed converges to the
 * <em>largest {@code maxSize}</em> distinct keys — a top-N / sliding window in O(n log n) time and bounded heap.
 * The architecture's streaming path — see docs/architecture-csrbt-integration.md §2.3.
 *
 * <p>{@code maxSize <= 0} means "unbounded": no {@code setMaxSize} call, so this degrades to a batched,
 * periodically self-healed feed. Uses only CSRBT's public bounded-set API — no CSRBT changes.
 */
public final class StreamingFeeder<K> implements SortFeeder<K> {

    private final int maxSize;
    private final HealthPolicy policy;

    /** Unbounded, default health policy — the form the engine's mode table can construct. */
    public StreamingFeeder() {
        this(0, HealthPolicy.defaults());
    }

    public StreamingFeeder(int maxSize) {
        this(maxSize, HealthPolicy.defaults());
    }

    public StreamingFeeder(int maxSize, HealthPolicy policy) {
        this.maxSize = maxSize;
        this.policy = (policy == null) ? HealthPolicy.defaults() : policy;
    }

    @Override
    public FeedResult feed(List<K> sortedRun, CsrbtTarget<K> target) {
        long start = System.nanoTime();
        if (maxSize > 0) {
            if (!target.supportsWindow()) {
                // Fail loudly rather than silently streaming unbounded: a caller who asked for a
                // bounded window on a target with no window (e.g. an EnsembleOrderedSet) would
                // otherwise get quietly unlimited growth — the worst kind of surprise.
                throw new IllegalArgumentException(
                        "bounded streaming (maxSize=" + maxSize + ") requires a windowed target "
                        + "(an OrderedSet); this target does not support setMaxSize. "
                        + "Feed the ensemble unbounded, or stream into an OrderedSet.");
            }
            target.setMaxSize(maxSize); // bound the target: FIFO eviction keeps the window at maxSize
        }
        int n = sortedRun.size();
        int inserted = 0;
        int checks = 0;
        boolean healthy = true;
        int batchIndex = 0;
        for (int from = 0; from < n; from += policy.batchSize()) {
            int to = Math.min(n, from + policy.batchSize());
            for (int i = from; i < to; i++) {
                if (target.add(sortedRun.get(i))) {
                    inserted++;
                }
            }
            batchIndex++;
            if (policy.validateEvery() > 0 && batchIndex % policy.validateEvery() == 0
                    && target.supportsHealthCheck()) {
                checks++;
                healthy = target.checkHealth();
            }
        }
        long elapsed = System.nanoTime() - start;
        int duplicates = n - inserted;
        return new FeedResult(FeedMode.STREAMING, n, inserted, duplicates, checks, healthy, elapsed);
    }

    @Override
    public FeedMode mode() {
        return FeedMode.STREAMING;
    }
}
