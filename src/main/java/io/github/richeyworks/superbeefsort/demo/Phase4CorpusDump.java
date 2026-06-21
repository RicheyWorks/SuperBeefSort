package io.github.richeyworks.superbeefsort.demo;

import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.profile.IntelligentDataProfiler;
import io.github.richeyworks.superbeefsort.profile.ProfileDepth;
import io.github.richeyworks.superbeefsort.strategy.CountingSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.HeapSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.InsertionSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.IntroSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.JdkSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.LearnedSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.MergeSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.RadixSortStrategy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Phase 4 training-corpus dumper (ADR docs/adr-phase4-python-intelligence.md, action item 3).
 *
 * <p>Emits a labeled CSV of {@code {DataProfile features, oracle-cheapest strategy, per-strategy
 * comparisons+moves}} over a configurable workload grid, using the <em>real</em> profiler features
 * (HLL distinct estimate, sampled inversions, distribution classification) — the feature-parity
 * corpus the offline Python trainer ({@code tools/phase4/train_selector.py}) fits on, so the model
 * is trained on exactly the features a {@code LearnedModelStrategySelector} computes at selection
 * time.</p>
 *
 * <p>The shape generators, profiler call, and brute-force oracle are identical to
 * {@link Phase4DecisionGate} (so the labels match the gate's published spread); this class only adds
 * a wider grid and the feature/label/cost CSV emission. The column order is the contract shared with
 * {@code tools/phase4/gen_corpus.py} (its {@code ALL_COLUMNS}).</p>
 *
 * <pre>{@code
 *   ./gradlew phase4Corpus                  # default grid -> two CSVs in build/reports/
 *   ./gradlew phase4Corpus --args="8"       # 8 trials per small-size cell
 * }</pre>
 *
 * <p>Writes {@code build/reports/phase4-corpus-train.csv} (a grid disjoint-by-size from the gate)
 * and {@code build/reports/phase4-corpus-gate.csv} (the exact 324 gate workloads, real features —
 * the held-out benchmark). Point the trainer at them:
 * {@code python3 train_selector.py phase4-corpus-train.csv phase4-corpus-gate.csv}.</p>
 */
public final class Phase4CorpusDump {

    private static final Comparator<Integer> CMP = Comparator.naturalOrder();
    private static final KeyEncoder<Integer> ENCODER = KeyEncoder.ofInt(i -> i);
    private static final IntelligentDataProfiler<Integer> PROFILER = new IntelligentDataProfiler<>();

    /** Candidate strategy ids in the gate's exact oracle order (drives tie-breaking + CSV cost cols). */
    private static final String[] STRATEGIES = {
            "insertion", "merge", "heap", "intro", "jdk.timsort", "counting", "radix.lsd", "learned"
    };

    enum Shape {
        SORTED, REVERSED, ALL_EQUAL, NEARLY_SORTED,
        SAWTOOTH, ORGAN_PIPE, FEW_DISTINCT, RANDOM, CLUSTERED
    }

    // Gate (TEST) grid — identical to Phase4DecisionGate.
    private static final int[] GATE_SIZES = {100, 500, 1000, 5000, 10000, 50000};
    private static final int GATE_TRIALS = 3;

    // Train grid — disjoint-by-size from the gate + a salt so no workload coincides.
    private static final int[] TRAIN_SIZES = {64, 128, 256, 400, 750, 1500, 3000, 6000, 12000, 20000};
    private static final long TRAIN_SALT = 7_000_003L;

    public static void main(String[] args) throws IOException {
        int trainTrials = args.length > 0 ? Integer.parseInt(args[0]) : 6;
        int largeTrials = Math.max(2, trainTrials / 2); // fewer trials at the costly large sizes

        Path dir = Path.of("build", "reports");
        Files.createDirectories(dir);

        Path gateCsv = dir.resolve("phase4-corpus-gate.csv");
        Path trainCsv = dir.resolve("phase4-corpus-train.csv");

        int gateRows = dumpGate(gateCsv);
        int trainRows = dumpTrain(trainCsv, trainTrials, largeTrials);

        System.out.printf("Phase 4 corpus: %d gate rows -> %s%n", gateRows, gateCsv);
        System.out.printf("Phase 4 corpus: %d train rows -> %s%n", trainRows, trainCsv);
        System.out.println("Train the model: cd tools/phase4 && "
                + "python3 train_selector.py " + trainCsv.getFileName() + " " + gateCsv.getFileName());
    }

    private static int dumpGate(Path csv) throws IOException {
        int rows = 0;
        try (var w = Files.newBufferedWriter(csv)) {
            w.write(header());
            for (int n : GATE_SIZES) {
                for (Shape shape : Shape.values()) {
                    for (boolean withKeys : new boolean[]{true, false}) {
                        for (int t = 0; t < GATE_TRIALS; t++) {
                            long seed = gateSeed(shape, n, t, withKeys);
                            w.write(row(shape, n, withKeys, seed));
                            rows++;
                        }
                    }
                }
            }
        }
        return rows;
    }

    private static int dumpTrain(Path csv, int trials, int largeTrials) throws IOException {
        int rows = 0;
        try (var w = Files.newBufferedWriter(csv)) {
            w.write(header());
            for (int n : TRAIN_SIZES) {
                int t = (n >= 10000) ? largeTrials : trials;
                for (Shape shape : Shape.values()) {
                    for (boolean withKeys : new boolean[]{true, false}) {
                        for (int trial = 0; trial < t; trial++) {
                            long seed = gateSeed(shape, n, trial, withKeys) + TRAIN_SALT;
                            w.write(row(shape, n, withKeys, seed));
                            rows++;
                        }
                    }
                }
            }
        }
        return rows;
    }

    /** Same seed formula as Phase4DecisionGate (so the gate grid reproduces bit-for-bit). */
    private static long gateSeed(Shape shape, int n, int trial, boolean withKeys) {
        return (long) shape.ordinal() * 137 + n * 31L + trial + (withKeys ? 0 : 999_999);
    }

    // ---- one CSV row: meta + features + oracle label + per-strategy costs ---- //

    private static String header() {
        StringBuilder sb = new StringBuilder();
        sb.append("shape,n,with_keys,seed,");
        sb.append("size,sortedness_ratio,has_duplicates,distinct_estimate,distinct_ratio,")
          .append("has_key_stats,key_span,counting_feasible,distribution_ord,longest_run,")
          .append("longest_run_ratio,inversions,inversion_ratio,inversions_exact,has_byte_key,");
        sb.append("oracle");
        for (String s : STRATEGIES) sb.append(",cost_").append(s);
        return sb.append('\n').toString();
    }

    private static String row(Shape shape, int n, boolean withKeys, long seed) {
        List<Integer> data = generate(shape, n, seed);
        DataProfile p = profile(data, withKeys);
        Map<String, Long> costs = oracle(data, p);
        String best = costs.entrySet().stream()
                .min(Map.Entry.comparingByValue()).orElseThrow().getKey();

        double maxInv = p.maxInversions() <= 0 ? 0.0 : (double) p.maxInversions();
        long keySpan = p.hasKeyStats() ? p.keyStats().span() : -1L;
        int countingFeasible = (p.hasKeyStats() && p.keyStats().countingFeasible()) ? 1 : 0;
        double invRatio = (p.inversions() >= 0 && maxInv > 0) ? p.inversions() / maxInv : 0.0;

        StringBuilder sb = new StringBuilder(256);
        sb.append(shape.name().toLowerCase(Locale.ROOT)).append(',')
          .append(n).append(',').append(withKeys ? 1 : 0).append(',').append(seed).append(',')
          // features
          .append(p.size()).append(',')
          .append(fmt(p.sortednessRatio())).append(',')
          .append(p.hasDuplicatesSampled() ? 1 : 0).append(',')
          .append(p.distinctEstimate()).append(',')
          .append(fmt(p.size() > 0 ? (double) p.distinctEstimate() / p.size() : 0.0)).append(',')
          .append(p.hasKeyStats() ? 1 : 0).append(',')
          .append(keySpan).append(',')
          .append(countingFeasible).append(',')
          .append(p.distribution().ordinal()).append(',')
          .append(p.longestRun()).append(',')
          .append(fmt(p.size() > 0 ? (double) p.longestRun() / p.size() : 0.0)).append(',')
          .append(p.inversions()).append(',')
          .append(fmt(invRatio)).append(',')
          .append(p.inversionsExact() ? 1 : 0).append(',')
          .append(p.hasByteSequenceKey() ? 1 : 0).append(',')
          // label
          .append(best);
        for (String s : STRATEGIES) {
            Long c = costs.get(s);
            sb.append(',').append(c == null ? "" : Long.toString(c));
        }
        return sb.append('\n').toString();
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }

    // ---- profiling / measurement / oracle — identical to Phase4DecisionGate ---- //

    private static DataProfile profile(List<Integer> data, boolean withKeys) {
        SortBuffer<Integer> buf = withKeys
                ? SortBuffer.of(new ArrayList<>(data), CMP, ENCODER)
                : SortBuffer.of(new ArrayList<>(data), CMP);
        return PROFILER.profile(buf, ProfileDepth.SHALLOW);
    }

    private static long[] measure(List<Integer> data, DataProfile profile, SortStrategy<Integer> s) {
        SortBuffer<Integer> buf = profile.hasKeyStats()
                ? SortBuffer.of(new ArrayList<>(data), CMP, ENCODER)
                : SortBuffer.of(new ArrayList<>(data), CMP);
        s.sort(buf, SortContext.noop());
        return new long[]{buf.comparisons(), buf.moves()};
    }

    private static Map<String, Long> oracle(List<Integer> data, DataProfile profile) {
        Map<String, Long> costs = new LinkedHashMap<>();
        for (SortStrategy<Integer> s : oracleCandidates(data.size(), profile)) {
            long[] m = measure(data, profile, s);
            costs.put(s.id().value(), m[0] + m[1]);
        }
        return costs;
    }

    private static List<SortStrategy<Integer>> oracleCandidates(int n, DataProfile profile) {
        List<SortStrategy<Integer>> c = new ArrayList<>();
        if (n <= 1000 || (profile.inversionsExact() && profile.inversions() <= 2L * n)) {
            c.add(new InsertionSortStrategy<>());
        }
        // network omitted: never applies at the corpus's sizes (n >= 64 with n > 16 everywhere here)
        c.add(new MergeSortStrategy<>());
        c.add(new HeapSortStrategy<>());
        c.add(new IntroSortStrategy<>());
        c.add(new JdkSortStrategy<>());
        if (profile.hasKeyStats()) {
            if (profile.keyStats().countingFeasible()) c.add(new CountingSortStrategy<>());
            c.add(new RadixSortStrategy<>());
            c.add(new LearnedSortStrategy<>());
        }
        return c;
    }

    // ---- shape generators — identical to Phase4DecisionGate ---- //

    private static List<Integer> generate(Shape shape, int n, long seed) {
        return switch (shape) {
            case SORTED -> sorted(n);
            case REVERSED -> reversed(n);
            case ALL_EQUAL -> allEqual(n);
            case NEARLY_SORTED -> nearlySorted(n, seed);
            case SAWTOOTH -> sawtooth(n, 8);
            case ORGAN_PIPE -> organPipe(n);
            case FEW_DISTINCT -> fewDistinct(n, 4);
            case RANDOM -> random(n, seed);
            case CLUSTERED -> clustered(n, seed);
        };
    }

    private static List<Integer> sorted(int n) {
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) a.add(i);
        return a;
    }

    private static List<Integer> reversed(int n) {
        List<Integer> a = new ArrayList<>(n);
        for (int i = n - 1; i >= 0; i--) a.add(i);
        return a;
    }

    private static List<Integer> allEqual(int n) {
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) a.add(42);
        return a;
    }

    private static List<Integer> nearlySorted(int n, long seed) {
        List<Integer> a = sorted(n);
        Random rng = new Random(seed);
        int swaps = Math.max(1, n / 20);
        for (int i = 0; i < swaps; i++) Collections.swap(a, rng.nextInt(n), rng.nextInt(n));
        return a;
    }

    private static List<Integer> sawtooth(int n, int period) {
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) a.add(i % period);
        return a;
    }

    private static List<Integer> organPipe(int n) {
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) a.add(Math.min(i, n - 1 - i));
        return a;
    }

    private static List<Integer> fewDistinct(int n, int distinct) {
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) a.add(i % distinct);
        return a;
    }

    private static List<Integer> random(int n, long seed) {
        Random rng = new Random(seed);
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) a.add(rng.nextInt(Math.max(1, 2 * n)));
        return a;
    }

    private static List<Integer> clustered(int n, long seed) {
        Random rng = new Random(seed);
        int clusters = Math.max(4, n / 20);
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) a.add(rng.nextInt(clusters) * 100 + rng.nextInt(10));
        return a;
    }

    private Phase4CorpusDump() {
    }
}
