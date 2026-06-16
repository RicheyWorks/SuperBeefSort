package io.github.richeyworks.superbeefsort.stream;

/**
 * A sequential concept-drift detector over a stream of per-batch {@link DriftSignal}s. It keeps a
 * <em>reference</em> signal — the distribution the current sort strategy was chosen for — and declares
 * drift when a new batch's {@link DriftSignal#distanceTo(DriftSignal) distance} from that reference
 * reaches {@code threshold}. On drift it re-baselines the reference to the new batch, so the detector
 * tracks the live regime rather than the original one; gradual drift therefore still fires once the
 * cumulative gap crosses the threshold.
 *
 * <p>Two anti-thrash guards mirror CSRBT's {@code MorphPolicy}: a {@code warmup} of initial batches
 * that establish the baseline without ever firing, and a {@code cooldown} of batches after a fire
 * during which drift is suppressed (so an oscillating stream cannot re-select every batch). The very
 * first batch always returns {@code drift = true} ("initial baseline") so the driver makes its first,
 * mandatory selection.</p>
 *
 * <p>Not thread-safe: a detector belongs to a single {@link AdaptiveStreamSorter} feeding one stream.</p>
 */
public final class DriftDetector {

    /** Default drift threshold: a single facet moving by 0.20 (of its normalized range) is drift. */
    public static final double DEFAULT_THRESHOLD = 0.20;

    private final double threshold;
    private final int warmup;   // batches (incl. the first) that only establish the baseline
    private final int cooldown; // batches after a fire during which drift is suppressed

    private DriftSignal reference;
    private long observed;
    private int sinceFire; // batches since the last fire (or since start)

    /** Detector with the default threshold, warmup of 1, and no cooldown. */
    public DriftDetector() {
        this(DEFAULT_THRESHOLD, 1, 0);
    }

    /** Detector with a custom threshold, warmup of 1, and no cooldown. */
    public DriftDetector(double threshold) {
        this(threshold, 1, 0);
    }

    /**
     * @param threshold drift fires when a batch's distance to the reference is {@code >= threshold} (in {@code (0,1]})
     * @param warmup    number of leading batches that only (re)establish the baseline, never firing ({@code >= 1})
     * @param cooldown  batches after a fire during which drift is suppressed ({@code >= 0})
     */
    public DriftDetector(double threshold, int warmup, int cooldown) {
        if (!(threshold > 0.0) || threshold > 1.0) {
            throw new IllegalArgumentException("threshold must be in (0,1]");
        }
        if (warmup < 1) {
            throw new IllegalArgumentException("warmup must be >= 1");
        }
        if (cooldown < 0) {
            throw new IllegalArgumentException("cooldown must be >= 0");
        }
        this.threshold = threshold;
        this.warmup = warmup;
        this.cooldown = cooldown;
    }

    /**
     * Test the next batch's signal against the reference and update internal state. Returns a
     * {@link DriftVerdict}; when {@code drift} is true the caller should re-select the strategy.
     */
    public DriftVerdict test(DriftSignal signal) {
        observed++;
        if (reference == null) {
            reference = signal;
            sinceFire = 0;
            return new DriftVerdict(true, 0.0, threshold, "initial baseline");
        }

        double score = signal.distanceTo(reference);
        sinceFire++;

        if (observed <= warmup) {
            reference = signal; // still warming up: track the latest batch, do not fire
            return new DriftVerdict(false, score, threshold, "warmup (" + observed + "/" + warmup + ")");
        }
        if (score < threshold) {
            return new DriftVerdict(false, score, threshold, "stable");
        }
        if (sinceFire <= cooldown) {
            return new DriftVerdict(false, score, threshold,
                    String.format("drift %.3f >= %.2f suppressed (cooldown %d/%d)", score, threshold, sinceFire, cooldown));
        }
        reference = signal; // adopt the new regime as the baseline
        sinceFire = 0;
        return new DriftVerdict(true, score, threshold, String.format("drift %.3f >= %.2f", score, threshold));
    }

    /** The live reference signal (the distribution the current strategy was chosen for), or null before the first batch. */
    public DriftSignal reference() {
        return reference;
    }

    public double threshold() {
        return threshold;
    }

    public int warmup() {
        return warmup;
    }

    public int cooldown() {
        return cooldown;
    }

    /** Forget all history, as if no batch had been seen — the next {@link #test} re-establishes the baseline. */
    public void reset() {
        reference = null;
        observed = 0;
        sinceFire = 0;
    }
}
