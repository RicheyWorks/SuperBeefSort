package io.github.richeyworks.superbeefsort.stream;

import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.profile.Distribution;
import io.github.richeyworks.superbeefsort.profile.KeyStats;

/**
 * A compact, scale-normalized fingerprint of one batch's distribution, distilled from a
 * {@link DataProfile} so successive batches can be compared cheaply for <em>concept drift</em> — a
 * material change in the data's shape that may invalidate the strategy chosen for an earlier batch.
 *
 * <p>Every facet is held in a comparable, bounded form so {@link #distanceTo(DriftSignal)} can combine
 * them into a single score in {@code [0,1]}:</p>
 * <ul>
 *   <li>{@code sortednessRatio} and {@code inversionRatio} — local and global disorder (both already
 *       in {@code [0,1]} on the profile);</li>
 *   <li>{@code cardinalityRatio} — distinct estimate over size, clamped to {@code [0,1]};</li>
 *   <li>{@code distribution} — the coarse {@link Distribution} class (categorical);</li>
 *   <li>{@code keyCenter} / {@code keySpan} — the integer-key range's location and scale (as doubles,
 *       overflow-safe), present only when the profile carried faithful {@link KeyStats}.</li>
 * </ul>
 *
 * <p>The distance is a <em>max</em> over facet distances, not an average: drift is "any one facet of
 * the distribution moved materially", so a single strong shift (e.g. the key range jumping to a new
 * decade) is never diluted by facets that happen to be stable. This is the data-side analogue of the
 * workload signal CSRBT's {@code MorphController} watches on the tree side.</p>
 */
public record DriftSignal(
        int size,
        double sortednessRatio,
        double inversionRatio,
        double cardinalityRatio,
        Distribution distribution,
        boolean hasKeyStats,
        double keyCenter,
        double keySpan) {

    /** Distance contributed when two batches fall in different (known) {@link Distribution} classes. */
    public static final double DISTRIBUTION_CLASS_DISTANCE = 0.5;
    /** Distance contributed when integer-key stats are available for one batch but not the other. */
    public static final double KEY_STATS_FLIP_DISTANCE = 0.5;

    /** Distil a drift fingerprint from a freshly measured {@link DataProfile}. */
    public static DriftSignal from(DataProfile p) {
        int n = p.size();
        double cardinality = n <= 0 ? 0.0 : clamp01((double) p.distinctEstimate() / n);
        KeyStats ks = p.keyStats();
        if (ks == null) {
            return new DriftSignal(n, p.sortednessRatio(), p.inversionRatio(), cardinality,
                    p.distribution(), false, Double.NaN, Double.NaN);
        }
        // Compute center as min/2 + max/2 (not (min+max)/2) so a wide signed range cannot overflow.
        double center = ks.min() / 2.0 + ks.max() / 2.0;
        double span = (double) ks.max() - (double) ks.min();
        return new DriftSignal(n, p.sortednessRatio(), p.inversionRatio(), cardinality,
                p.distribution(), true, center, span);
    }

    /**
     * Combined drift distance to {@code ref} in {@code [0,1]} — the maximum over all facet distances.
     * Continuous facets use the absolute difference; the {@link Distribution} class and the presence of
     * key stats are categorical (a fixed distance when they differ); the key range contributes its
     * location and scale shift normalized by the larger of the two spans, so it is comparison-invariant.
     */
    public double distanceTo(DriftSignal ref) {
        double d = 0.0;
        d = Math.max(d, Math.abs(sortednessRatio - ref.sortednessRatio));
        d = Math.max(d, Math.abs(inversionRatio - ref.inversionRatio));
        d = Math.max(d, Math.abs(cardinalityRatio - ref.cardinalityRatio));

        if (distribution != ref.distribution
                && distribution != Distribution.UNKNOWN
                && ref.distribution != Distribution.UNKNOWN) {
            d = Math.max(d, DISTRIBUTION_CLASS_DISTANCE);
        }

        if (hasKeyStats != ref.hasKeyStats) {
            d = Math.max(d, KEY_STATS_FLIP_DISTANCE);
        } else if (hasKeyStats) {
            double denom = Math.max(1.0, Math.max(keySpan, ref.keySpan));
            d = Math.max(d, Math.abs(keyCenter - ref.keyCenter) / denom);
            d = Math.max(d, Math.abs(keySpan - ref.keySpan) / denom);
        }
        return clamp01(d);
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v) || v < 0.0) {
            return 0.0;
        }
        return v > 1.0 ? 1.0 : v;
    }
}
