package io.github.richeyworks.superbeefsort.workload;

import io.github.richeyworks.superbeefsort.source.MiniJson;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    // ── Adversarial shapes (the anti-sorted menagerie wing) ─────────────────────────────────

    /** Organ pipe: ascend to the middle, descend back — one long run that betrays run-extenders. */
    public static List<Integer> organPipe(int n) {
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(Math.min(i, n - 1 - i));
        }
        return out;
    }

    /** Zigzag low-high alternation: every run has length 1 — maximal hostility to run detection. */
    public static List<Integer> zigzag(int n) {
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add((i & 1) == 0 ? i / 2 : n - 1 - i / 2);
        }
        return out;
    }

    /** Every element equal — the degenerate duplicate case (stability and dedup food). */
    public static List<Integer> allEqual(int n, int value) {
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(value);
        }
        return out;
    }

    // ── String shapes (byte-sequence / MSD-radix food) ──────────────────────────────────────

    /**
     * URL-ish paths with heavy shared prefixes ({@code /segNN/segNN/.../leafNNN}) — the shape MSD
     * radix loves (early bytes discriminate slowly, then fan out) and comparison sorts pay
     * long-common-prefix tax on.
     */
    public static List<String> paths(int n, int depth, int fanout, long seed) {
        if (depth < 1 || fanout < 1) throw new IllegalArgumentException("depth/fanout must be >= 1");
        Random rnd = new Random(seed);
        List<String> out = new ArrayList<>(n);
        StringBuilder sb = new StringBuilder(depth * 8);
        for (int i = 0; i < n; i++) {
            sb.setLength(0);
            for (int d = 0; d < depth; d++) {
                sb.append("/seg").append(rnd.nextInt(fanout));
            }
            sb.append("/leaf").append(rnd.nextInt(1_000));
            out.add(sb.toString());
        }
        return out;
    }

    /** Deterministic pseudo-UUIDs (hex-8-4-4-4-12) — uniformly distributed early bytes. */
    public static List<String> uuids(int n, long seed) {
        Random rnd = new Random(seed);
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(String.format("%08x-%04x-%04x-%04x-%012x",
                    rnd.nextInt(), rnd.nextInt(1 << 16), rnd.nextInt(1 << 16),
                    rnd.nextInt(1 << 16), rnd.nextLong() & 0xFFFFFFFFFFFFL));
        }
        return out;
    }

    /** Zero-padded fixed-width numerics — byte order equals numeric order, radix's best case. */
    public static List<String> paddedNumbers(int n, int width, long seed) {
        if (width < 1 || width > 18) throw new IllegalArgumentException("width must be in [1,18]");
        Random rnd = new Random(seed);
        long bound = (long) Math.pow(10, width);
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(String.format("%0" + width + "d", Math.floorMod(rnd.nextLong(), bound)));
        }
        return out;
    }

    /** Variable-length lowercase words (3–12 chars) — the mixed-length dictionary shape. */
    public static List<String> words(int n, long seed) {
        Random rnd = new Random(seed);
        List<String> out = new ArrayList<>(n);
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < n; i++) {
            sb.setLength(0);
            int len = 3 + rnd.nextInt(10);
            for (int c = 0; c < len; c++) {
                sb.append((char) ('a' + rnd.nextInt(26)));
            }
            out.add(sb.toString());
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

    /**
     * Endless Zipf(s)-distributed keys over {@code [0, keySpace)} — the stream twin of
     * {@link #zipf}: head-heavy access with a long tail, the shape real caches and hot rows show.
     */
    public static IntSupplier zipfKeys(int keySpace, double s, long seed) {
        if (keySpace < 1 || keySpace > (1 << 20)) {
            throw new IllegalArgumentException("keySpace must be in [1, 2^20]: " + keySpace);
        }
        double[] cdf = new double[keySpace];
        double sum = 0.0;
        for (int r = 1; r <= keySpace; r++) {
            sum += 1.0 / Math.pow(r, s);
            cdf[r - 1] = sum;
        }
        double total = sum;
        Random rnd = new Random(seed);
        return () -> {
            double u = rnd.nextDouble() * total;
            int lo = 0;
            int hi = keySpace - 1;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (cdf[mid] < u) lo = mid + 1; else hi = mid;
            }
            return lo;
        };
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

    /** ~80% Zipf-distributed reads: head-heavy skew with a long tail — between uniform and hot-set. */
    public static Regime zipfRead(int ops, int keySpace, double s, long seed) {
        return Regime.of("zipf reads (s=" + s + ")", ops, 0.80, 0.5, zipfKeys(keySpace, s, seed));
    }

    /**
     * A {@link Regime} that replays a recorded trace (see {@link TraceRecorder}) so the aquarium
     * can eat a <em>real</em> access pattern instead of a synthesized one. The trace's keys become
     * a cyclic key stream (wrapping back to the start when exhausted) and its op counts set the
     * regime's aggregate mix: {@code readFraction} = fraction of {@code contains} ops,
     * {@code addBias} = {@code add} / ({@code add} + {@code remove}). {@code ops} is set to the
     * trace length, so one regime pass replays the trace's key stream exactly once.
     *
     * <p>Because a {@link Regime} models an op <em>mix</em> (probabilities) rather than an explicit
     * op <em>sequence</em>, the exact per-op pairing of key↔op is not preserved — the key order and
     * the aggregate read/write/add shape are. For an exact op-by-op replay against a live set, use
     * {@link TraceReplayer#replay} instead.
     *
     * @throws IllegalArgumentException if the trace has no ops
     */
    public static Regime fromTrace(Path path) throws IOException {
        List<Integer> keys = new ArrayList<>();
        int adds = 0;
        int removes = 0;
        int contains = 0;
        try (BufferedReader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            long lineNumber = 0;
            while ((line = in.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                String op = MiniJson.field(line, "op");
                String keyRaw = MiniJson.field(line, "key");
                if (op == null || keyRaw == null) {
                    throw new IOException("trace line " + lineNumber + " missing 'op' or 'key': " + line);
                }
                keys.add(Integer.parseInt(keyRaw.trim()));
                switch (op) {
                    case "add":      adds++;     break;
                    case "remove":   removes++;  break;
                    case "contains": contains++; break;
                    default:
                        throw new IOException("trace line " + lineNumber
                                + " has unknown op '" + op + "': " + line);
                }
            }
        }
        int total = keys.size();
        if (total == 0) {
            throw new IllegalArgumentException("empty trace (no ops): " + path);
        }
        int[] keyArray = new int[total];
        for (int i = 0; i < total; i++) {
            keyArray[i] = keys.get(i);
        }
        int[] cursor = {0};
        IntSupplier stream = () -> {
            int i = cursor[0];
            cursor[0] = (i + 1) % keyArray.length;      // cyclic; cursor never leaves [0, len)
            return keyArray[i];
        };
        int writes = adds + removes;
        double readFraction = (double) contains / total;
        double addBias = (writes == 0) ? 0.5 : (double) adds / writes;
        return Regime.of("trace(" + path.getFileName() + ", " + total + " ops)",
                total, readFraction, addBias, stream);
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
