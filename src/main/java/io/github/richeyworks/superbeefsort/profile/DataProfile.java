package io.github.richeyworks.superbeefsort.profile;

/**
 * Measured characteristics of an input, produced by a {@link DataProfiler} and consumed by the
 * selector. Phase 1 adds a distinct-count estimate, integer {@link KeyStats} (when a faithful key
 * encoder is available), and a {@link Distribution} classification; {@code longestRun} carries the
 * length of the single longest ascending (non-decreasing) run -- a run-aware signal that complements
 * the adjacency-based {@link #sortednessRatio()} (architecture sec 5.2).
 *
 * <p>{@code inversions} is a <em>global</em> disorder signal: the number of out-of-order pairs
 * (i&lt;j with {@code a[i] > a[j]}), i.e. the exact work an adaptive insertion sort would do. Unlike
 * {@link #sortednessRatio()} (a local, adjacent-pair measure) it sees distant disorder, so a "locally
 * tidy" input with far-apart swaps is no longer mistaken for nearly sorted. It is measured exactly for
 * small or {@link ProfileDepth#DEEP} inputs and estimated from a strided sample otherwise;
 * {@link #inversionsExact()} says which. {@code inversions == -1} means unmeasured.</p>
 */
public record DataProfile(
        int size,
        double sortednessRatio,
        boolean hasDuplicatesSampled,
        ProfileDepth depth,
        long distinctEstimate,
        KeyStats keyStats,
        Distribution distribution,
        int longestRun,
        long inversions,
        boolean inversionsExact,
        boolean hasByteSequenceKey) {

    /** Back-compat for callers using the previous 10-arg canonical (adds {@code hasByteSequenceKey = false}). */
    public DataProfile(int size, double sortednessRatio, boolean hasDuplicatesSampled, ProfileDepth depth,
                       long distinctEstimate, KeyStats keyStats, Distribution distribution,
                       int longestRun, long inversions, boolean inversionsExact) {
        this(size, sortednessRatio, hasDuplicatesSampled, depth, distinctEstimate, keyStats,
             distribution, longestRun, inversions, inversionsExact, false);
    }

    /** Back-compat constructor for profilers that measure neither runs nor inversions (both default to unmeasured). */
    public DataProfile(int size, double sortednessRatio, boolean hasDuplicatesSampled, ProfileDepth depth,
                       long distinctEstimate, KeyStats keyStats, Distribution distribution) {
        this(size, sortednessRatio, hasDuplicatesSampled, depth, distinctEstimate, keyStats, distribution, 0, -1L, false, false);
    }

    /** Back-compat constructor for profilers that measure runs but not inversions ({@code inversions} unmeasured). */
    public DataProfile(int size, double sortednessRatio, boolean hasDuplicatesSampled, ProfileDepth depth,
                       long distinctEstimate, KeyStats keyStats, Distribution distribution, int longestRun) {
        this(size, sortednessRatio, hasDuplicatesSampled, depth, distinctEstimate, keyStats, distribution, longestRun, -1L, false, false);
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

    /** The maximum possible number of inversions for this size, {@code C(n,2) = n(n-1)/2}. */
    public long maxInversions() {
        return size <= 1 ? 0L : (long) size * (size - 1) / 2L;
    }

    /** True when an inversion count (exact or sampled) is available on this profile. */
    public boolean inversionsMeasured() {
        return inversions >= 0;
    }

    /**
     * Normalized inversion distance in {@code [0,1]} (the Kendall-tau distance): {@code 0} = sorted,
     * {@code ~0.5} = random, {@code 1} = reversed. When no inversion count was measured it falls back
     * to the adjacency complement {@code 1 - sortednessRatio}, so callers always get a usable
     * global-disorder signal without having to branch on {@link #inversionsMeasured()}.
     */
    public double inversionRatio() {
        if (size <= 1) {
            return 0.0;
        }
        if (inversions < 0) {
            double approx = 1.0 - sortednessRatio;
            return approx < 0.0 ? 0.0 : (approx > 1.0 ? 1.0 : approx);
        }
        long max = maxInversions();
        if (max <= 0) {
            return 0.0;
        }
        double r = (double) inversions / max;
        return r < 0.0 ? 0.0 : (r > 1.0 ? 1.0 : r);
    }
}
