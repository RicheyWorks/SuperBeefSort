package io.github.richeyworks.superbeefsort.feed;

/** The "personality" of how a sorted run is inserted into CSRBT. */
public enum FeedMode {
    /** Sequential {@code add()} in sorted order — simplest, but skews a balanced tree. */
    DIRECT,
    /** Median-first balanced insertion — minimizes rotations for a sorted run. */
    BALANCED,
    /** O(n) build via CSRBT {@code fromSorted} when the target is an empty OrderedSet; else BALANCED. */
    BULK,
    /** Balanced insertion in batches, validating/repairing tree health between batches. */
    HEALTH_GATED,
    /** Median-first insertion that validates CSRBT health after every insert; explicit duplicate accounting. */
    PRECISION,
    /**
     * Median-first insertion aimed at an {@link io.github.richeyworks.csrbt.ensemble.EnsembleOrderedSet}
     * built with {@code parallelFanOut()}: each {@code add} fans out to all mirror members concurrently
     * (ADR-003 E5), so the per-member builds overlap with no SuperBeefSort-side threads.
     */
    PARALLEL,
    /**
     * Bounded sliding-window / top-N feed: the {@link StreamingFeeder} caps the target via
     * {@code OrderedSet.setMaxSize} (CSRBT FIFO-evicts the oldest-inserted key) and feeds the ascending
     * run in {@link HealthPolicy}-sized batches with self-heal backpressure, converging to the largest
     * {@code maxSize} distinct keys. {@code maxSize <= 0} streams unbounded.
     */
    STREAMING
}
