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
    PRECISION
}
