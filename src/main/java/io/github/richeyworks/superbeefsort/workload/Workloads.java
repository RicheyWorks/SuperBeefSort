package io.github.richeyworks.superbeefsort.workload;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntSupplier;

/**
 * The menagerie: named, deterministic workload generators for feeding the two engines —
 * <b>batch shapes</b> (lists with a specific statistical fingerprint, for the profiler → selector
 * side: sortedness, inversions, duplicates, distribution class) and <b>key streams / regimes</b>
 * (endless per-op traffic with a specific access fingerprint, for the tree-adaptation side:
 * read/write mix, hot-key skew, monotone climb). Every generator takes an explicit seed and is
 * reproducible; none of them knows anything about the engines — workloads are food, not behavior.
 *
 * <p>Batch shapes target specific selector routes (see {@code RuleBasedStrategySelector}):
 * {@link #nearlySorted} → TimSort's long-run galloping, {@link #reversed} → inversion-heavy,
 * {@link #duplicateHeavy} → counting/merge territory, {@link #sawtooth} → run detection,
 * {@link #timestamps} → the append-mostly time-series shape, {@link #zipf} → skew the profiler's
 * distinct-count estimator sees. Regimes target specific control-plane verdicts:
 * {@link #readHeavyUniform} favors strict balance, {@link #hotKeySkew} favors self-adjustment,
 * {@link #writeBurst} favors rebalance-lean shapes, {@link #windowedClimb} exercises FIFO
 * eviction under a bound.</p>
 */
public final class Workloads {

    private Workloads() {
    }

    // ── Batch shapes (sort-side food) ───────────────────────────────────────────────────────

    /** {@code n} uniform ints in {@code [0, bound)}. The baseline shape: no structure at all. */
    public static List<Integer> uniform(int n, int bound, long seed) {
        Random rnd = new Random(seed);
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(rnd.nextInt(bound));
        }
        return out;
    }

    /**
     * Ascending {@code 0..n-1} with {@code disorder}·n random transpositions — high sortedness,
     * few inversions: insertion/TimSort territory.
     */
    public static List<Integer> nearlySorted(int n, double disorder, long seed) {
        Random rnd = new Random(seed);
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(i);
        }
        int swaps = (int) Math.max(0, disorder * n);
        for (int s = 0; s < swaps; s++) {
            int i = rnd.nextInt(n);
            int j = rnd.nextInt(n);
            Integer t = out.get(i);
            out.set(i, out.get(j));
            out.set(j, t);
        }
        return out;
    }

    /** Strictly descending {@code n-1..0} — the maximal-inversions adversary. */
    public static List<Integer> reversed(int n) {
        List<Integer> out = new ArrayList<>(n);
        for (int i = n - 1; i >= 0; i--) {
            out.add(i);
        }
        return out;
    }

    /** {@code teeth} ascending runs of equal length — the run-detection shape. */
    public static List<Integer> sawtooth(int n, int teeth) {
        if (teeth < 1) throw new IllegalArgumentException("teeth must be >= 1: " + teeth);
        List<Integer> out = new ArrayList<>(n);
        int toothLen = Math.max(1, n / teeth);
        for (int i = 0; i < n; i++) {
            out.add(i % toothLen);
        }
        return out;
    }

    /** {@code n} draws over only {@code distinct} values — duplicate-heavy (dedup and stability food). */
    public static List<Integer> duplicateHeavy(int n, int distinct, long seed) {
        if (distinct < 1) throw new IllegalArgumentException("distinct must be >= 1: " + distinct);
        Random rnd = new Random(seed);
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(rnd.nextInt(distinct));
        }
        return out;
    }

    /**
     * Monotone "clock" with bounded jitter — the append-mostly time-series shape: ascending base
     * {@code i} plus a uniform perturbation in {@code [0, jitter]}, so inversions exist but only
     * within the jitter radius (out-of-order event arrival, never wholesale disorder).
     */
    public static List<Integer> timestamps(int n, int jitter, long seed) {
        Random rnd = new Random(seed);
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(i + rnd.nextInt(jitter + 1));
        }
        return out;
    }

    /**
     * {@code n} Zipf(s)-distributed ranks mapped into {@code [0, keySpace)} — the head-heavy skew
     * real access logs show. Exact inverse-CDF sampling over precomputed harmonic weights;
     * {@code keySpace} is capped at 2²⁰ to bound the table.
     */
    public static List<Integer> zipf(int n, int keySpace, double s, long seed) {
        if (keySpace < 1 || keySpace > (1 << 20)) {
            throw new IllegalArgumentException("keySpace must be in [1, 2^20]: " + keySpace);
        }
        double[] cdf = new double[keySpace];
        double sum = 0.0;
        for (int r = 1; r <= keySpace; r++) {
            sum += 1.0 / Math.pow(r, s);
            cdf[r - 1] = sum;
        }
        Random rnd = new Random(seed);
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double u = rnd.nextDouble() * sum;
            int lo = 0;
            int hi = keySpace - 1;
            while (lo < hi) {                      // first rank whose cdf >= u
                int mid = (lo + hi) >>> 1;
                if (cdf[mid] < u) lo = mid + 1; else hi = mid;
            }
            out.add(lo);
        }
        return out;
    }

    // ── Key streams (endless per-op key sources for regimes) ───────────────────────────────

    /** Endless uniform keys over {@code [0, keySpace)}. */
    public static IntSupplier uniformKeys(int keySpace, long seed) {
        Random rnd = new Random(seed);
        return () -> rnd.nextInt(keySpace);
    }

    /**
     * Endless hot-set keys: with probability {@code hotFraction} one of {@code hotCount} fixed
     * keys, else uniform over {@code [0, keySpace)} — temporal locality, splay food.
     */
    public static IntSupplier hotSetKeys(int hotCount, double hotFraction, int keySpace, long seed) {
        Random rnd = new Random(seed);
        int[] hot = new int[hotCount];
        for (int i = 0; i < hotCount; i++) {
            hot[i] = rnd.nextInt(keySpace);
        }
        return () -> rnd.nextDouble() < hotFraction ? hot[rnd.nextInt(hot.length)] : rnd.nextInt(keySpace);
    }

    /** Endless monotonically climbing keys with jitter — the append regime; pairs with a window. */
    public static IntSupplier climbingKeys(int jitter, long seed) {
        Random rnd = new Random(seed);
        int[] clock = {0};
        return () -> {
            clock[0] += 1 + rnd.nextInt(jitter + 1);
            return clock[0];
        };
    }

    // ── Ready regimes (control-plane food) ──────────────────────────────────────────────────

    /** ~85% uniform reads: the strict-balance (AVL) verdict's natural habitat. */
    public static Regime readHeavyUniform(int ops, int keySpace, long seed) {
        return Regime.of("read-heavy uniform", ops, 0.85, 0.5, uniformKeys(keySpace, seed));
    }

    /** ~90% reads concentrated on {@code hotCount} keys: self-adjustment (Splay) territory. */
    public static Regime hotKeySkew(int ops, int hotCount, int keySpace, long seed) {
        return Regime.of("hot-key skew (" + hotCount + " keys)", ops, 0.90, 0.5,
                hotSetKeys(hotCount, 0.9, keySpace, seed));
    }

    /** ~85% writes, insert-leaning: rebalance pressure, the write-tolerant shapes' habitat. */
    public static Regime writeBurst(int ops, int keySpace, long seed) {
        return Regime.of("write burst", ops, 0.15, 0.7, uniformKeys(keySpace, seed));
    }

    /** Climbing inserts under a sliding window of {@code window}: continuous FIFO eviction. */
    public static Regime windowedClimb(int ops, int window, long seed) {
        return new Regime("windowed climb (window=" + window + ")", ops, 0.20, 0.95,
                climbingKeys(3, seed), window);
    }

    /** Balanced churn over a mid-sized key space — the "nothing special" control regime. */
    public static Regime steadyChurn(int ops, int keySpace, long seed) {
        return Regime.of("steady churn", ops, 0.50, 0.5, uniformKeys(keySpace, seed));
    }

    /** The default aquarium playlist: one lap through every habitat, then the window comes off. */
    public static List<Regime> aquariumPlaylist(long seed) {
        return List.of(
                readHeavyUniform(15_000, 60_000, seed),
                hotKeySkew(15_000, 8, 60_000, seed + 1),
                writeBurst(15_000, 60_000, seed + 2),
                windowedClimb(15_000, 8_000, seed + 3),
                new Regime("cooldown (window off)", 10_000, 0.60, 0.5,
                        uniformKeys(60_000, seed + 4), 0));
    }
}
