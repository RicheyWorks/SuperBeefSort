package io.github.richeyworks.superbeefsort.profile;

/**
 * Measured characteristics of an input, produced by a {@link DataProfiler} and consumed by the
 * selector. Phase 1 adds a distinct-count estimate, integer {@link KeyStats} (when a faithful key
 * encoder is available), and a {@link Distribution} classification; {@code longestRun} carries the
 * length of the single longest ascending (non-decreasing) run -- a run-aware signal that complements
 * the adjacency-based {@link #sortednessRatio()} (architecture sec 5.2).
 */
public record DataProfile(
        int size,
        double sortednessRatio,
        boolean hasDuplicatesSampled,
        ProfileDepth depth,
        long distinctEstimate,
        KeyStats keyStats,
        Distribution distribution,
        int longestRun) {

    /** Back-compat constructor for profilers that do not measure runs ({@code longestRun} defaults to 0). */
    public DataProfile(int size, double sortednessRatio, boolean hasDuplicatesSampled, ProfileDepth depth,
                       long distinctEstimate, KeyStats keyStats, Distribution distribution) {
        this(size, sortednessRatio, hasDuplicatesSampled, depth, distinctEstimate, keyStats, distribution, 0);
    }

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

    /** Fraction of the input spanned by its single longest ascending run (0 when unmeasured). */
    public double longestRunRatio() {
        return size <= 0 ? 0.0 : (double) longestRun / size;
    }
}
