package io.github.richeyworks.superbeefsort.profile;

/**
 * Measured characteristics of an input, produced by a {@link DataProfiler} and consumed by the
 * selector. Phase 1 adds a distinct-count estimate, integer {@link KeyStats} (when a faithful key
 * encoder is available), and a {@link Distribution} classification.
 */
public record DataProfile(
        int size,
        double sortednessRatio,
        boolean hasDuplicatesSampled,
        ProfileDepth depth,
        long distinctEstimate,
        KeyStats keyStats,
        Distribution distribution) {

    /** True when at least 90% of adjacent pairs are already in order. */
    public boolean nearlySorted() {
        return sortednessRatio >= 0.90;
    }

    public boolean tiny() {
        return size <= 16;
    }

    /** True when a faithful integer encoding was available, enabling non-comparison sorts. */
    public boolean hasKeyStats() {
        return keyStats != null;
    }
}
