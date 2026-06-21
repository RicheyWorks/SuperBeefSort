package io.github.richeyworks.superbeefsort.demo;

import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortResult;
import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.core.StrategyId;
import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.profile.IntelligentDataProfiler;
import io.github.richeyworks.superbeefsort.profile.ProfileDepth;
import io.github.richeyworks.superbeefsort.registry.StrategyRegistry;
import io.github.richeyworks.superbeefsort.select.BanditStrategySelector;
import io.github.richeyworks.superbeefsort.select.CostModelStrategySelector;
import io.github.richeyworks.superbeefsort.select.LearningStrategySelector;
import io.github.richeyworks.superbeefsort.select.SelectionPolicy;
import io.github.richeyworks.superbeefsort.select.SortPlan;
import io.github.richeyworks.superbeefsort.select.StrategySelector;
import io.github.richeyworks.superbeefsort.strategy.CountingSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.HeapSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.InsertionSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.IntroSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.JdkSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.LearnedSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.MergeSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.RadixSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.SortingNetworkStrategy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Phase 4 decision gate — ADR action item 2.
 *
 * <p>For a representative spread of workloads, measures how often {@link CostModelStrategySelector}
 * and {@link BanditStrategySelector} choose the same strategy as a brute-force oracle (minimum
 * {@code comparisons + moves} over all applicable strategies). Reports % exact-match and mean/max
 * regret = (chosen_cost - oracle_cost) / oracle_cost.</p>
 *
 * <p>If regret is small, Phase 4 (LearnedModelStrategySelector) is not justified — record that
 * in the ADR and close the action item. Run: {@code ./gradlew phase4Gate}</p>
 */
public final class Phase4DecisionGate {

    private static final int[] SIZES = {100, 500, 1000, 5000, 10000, 50000};
    private static final int TRIALS = 3; // per (size, shape, keyMode) cell

    private static final StrategyRegistry REGISTRY = StrategyRegistry.withDefaults();
    private static final Comparator<Integer> CMP = Comparator.naturalOrder();
    private static final KeyEncoder<Integer> ENCODER = KeyEncoder.ofInt(i -> i);
    private static final IntelligentDataProfiler<Integer> PROFILER = new IntelligentDataProfiler<>();

    enum Shape {
        SORTED, REVERSED, ALL_EQUAL, NEARLY_SORTED,
        SAWTOOTH, ORGAN_PIPE, FEW_DISTINCT, RANDOM, CLUSTERED
    }

    record Row(String selector, String shape, int n, boolean withKeys,
               String chosen, String oracle, long chosenCost, long oracleCost) {

        /** Relative cost excess over the oracle; 0 = optimal, 0.5 = 50% more expensive. */
        double regret() {
            return oracleCost == 0 ? 0 : (double) (chosenCost - oracleCost) / oracleCost;
        }

        boolean exactMatch() { return chosen.equals(oracle); }

        boolean nearOptimal() { return regret() < 0.05; } // within 5%
    }

    public static void main(String[] args) throws IOException {
        CostModelStrategySelector costModel = new CostModelStrategySelector();
        BanditStrategySelector bandit = new BanditStrategySelector();

        List<Row> rows = new ArrayList<>();
        int totalCells = SIZES.length * Shape.values().length * 2 * TRIALS;
        int done = 0;

        System.out.printf("Phase 4 Decision Gate — %d workloads × 2 selectors%n", totalCells);

        for (int n : SIZES) {
            System.out.printf("  n=%-6d ", n);
            for (Shape shape : Shape.values()) {
                for (boolean withKeys : new boolean[]{true, false}) {
                    for (int trial = 0; trial < TRIALS; trial++) {
                        long seed = (long) shape.ordinal() * 137 + n * 31L + trial + (withKeys ? 0 : 999_999);
                        List<Integer> data = generate(shape, n, seed);

                        DataProfile profile = profile(data, withKeys);
                        Map<String, Long> oracleCosts = oracle(data, profile);
                        Map.Entry<String, Long> best = oracleCosts.entrySet().stream()
                                .min(Map.Entry.comparingByValue()).orElseThrow();

                        for (var e : List.of(
                                Map.entry("cost-model", (StrategySelector) costModel),
                                Map.entry("bandit", (StrategySelector) bandit))) {

                            SortPlan plan = e.getValue().select(profile, SelectionPolicy.SMART, REGISTRY);
                            StrategyId chosenId = REGISTRY.contains(plan.strategy())
                                    ? plan.strategy() : plan.fallback();

                            long[] meas = measure(data, profile, REGISTRY.get(chosenId));
                            rows.add(new Row(e.getKey(), shape.name().toLowerCase(), n, withKeys,
                                    chosenId.value(), best.getKey(), meas[0] + meas[1], best.getValue()));

                            if (e.getValue() instanceof LearningStrategySelector ls) {
                                ls.observe(profile, chosenId,
                                        new SortResult(chosenId, n, meas[0], meas[1], 0L, 0L));
                            }
                        }
                        done++;
                    }
                }
            }
            System.out.println("done");
        }

        printReport(rows);
        writeCsv(rows);
    }

    // ---- profiling / measurement ----

    private static DataProfile profile(List<Integer> data, boolean withKeys) {
        SortBuffer<Integer> buf = withKeys
                ? SortBuffer.of(new ArrayList<>(data), CMP, ENCODER)
                : SortBuffer.of(new ArrayList<>(data), CMP);
        return PROFILER.profile(buf, ProfileDepth.SHALLOW);
    }

    /** Returns {comparisons, moves} for the given strategy on a fresh copy of data. */
    private static long[] measure(List<Integer> data, DataProfile profile, SortStrategy<Integer> s) {
        SortBuffer<Integer> buf = profile.hasKeyStats()
                ? SortBuffer.of(new ArrayList<>(data), CMP, ENCODER)
                : SortBuffer.of(new ArrayList<>(data), CMP);
        s.sort(buf, SortContext.noop());
        return new long[]{buf.comparisons(), buf.moves()};
    }

    /** Oracle: run all applicable strategies and return a strategyId → (comparisons+moves) map. */
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
        // Guard insertion against O(n²) on adversarial shapes: include only for small n or
        // exactly-measured near-sorted profiles (inversions ≤ 2n with exact count).
        if (n <= 1000 || (profile.inversionsExact() && profile.inversions() <= 2L * n)) {
            c.add(new InsertionSortStrategy<>());
        }
        if (n <= 16) c.add(new SortingNetworkStrategy<>());
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

    // ---- shape generators ----

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
        int swaps = Math.max(1, n / 20); // ~5% disorder
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

    // ---- reporting ----

    private static void printReport(List<Row> rows) {
        int perSelector = rows.size() / 2;
        System.out.printf("%n========================================%n");
        System.out.println("Phase 4 Decision Gate — Results");
        System.out.printf("========================================%n");
        System.out.printf("Workloads per selector: %d  (%d sizes × %d shapes × 2 key-modes × %d trials)%n%n",
                perSelector, SIZES.length, Shape.values().length, TRIALS);

        // Paranoia: verify the oracle is discriminating across workloads.
        printOracleSpread(rows);

        for (String sel : List.of("cost-model", "bandit")) {
            printSelector(sel, rows.stream().filter(r -> r.selector().equals(sel)).toList());
        }
    }

    /**
     * Print oracle winner distribution across all workloads and verify the oracle is discriminating.
     * If only one strategy ever wins, the harness is degenerate — "already optimal" would be
     * trivially true and untrustworthy.
     */
    private static void printOracleSpread(List<Row> rows) {
        // Collect oracle winners from cost-model rows (one oracle result per workload cell).
        List<Row> cmRows = rows.stream().filter(r -> r.selector().equals("cost-model")).toList();

        // Frequency of each oracle winner
        Map<String, Long> freq = new java.util.TreeMap<>();
        for (Row r : cmRows) freq.merge(r.oracle(), 1L, Long::sum);

        System.out.println("Oracle winner distribution (brute-force metered min across all candidates):");
        freq.forEach((k, v) -> System.out.printf("  %-22s %3d / %d%n", k, v, cmRows.size()));

        long distinctWinners = freq.size();
        System.out.printf("  -> %d distinct winners across %d workloads%n%n", distinctWinners, cmRows.size());

        // Paranoia assertion: if only one winner, the oracle never had a real contest — something is wrong.
        if (distinctWinners < 2) {
            throw new AssertionError(
                    "Oracle degenerate: only one strategy ever wins (" + freq.keySet() +
                    "). Check that all candidate strategies are metered through the SortBuffer.");
        }

        // Count workloads where oracle_cost > 0 (the oracle made a real choice between non-trivial strategies).
        long nonZeroCost = cmRows.stream().filter(r -> r.oracleCost() > 0).count();
        System.out.printf("Oracle cost > 0 in %d / %d workloads " +
                "(zero means all candidates unmetered — that is a bug)%n%n", nonZeroCost, cmRows.size());
        if (nonZeroCost == 0) {
            throw new AssertionError(
                    "Oracle cost is 0 in every workload. No strategy is metering through the SortBuffer — " +
                    "regret calculation is undefined. Fix strategy metering before trusting these results.");
        }
    }

    private static void printSelector(String name, List<Row> rows) {
        long total = rows.size();
        long exact = rows.stream().filter(Row::exactMatch).count();
        long differ = total - exact; // chose a different strategy than the oracle's cheapest
        long near = rows.stream().filter(Row::nearOptimal).count();
        double meanReg = rows.stream().mapToDouble(Row::regret).average().orElse(0);
        double maxReg = rows.stream().mapToDouble(Row::regret).max().orElse(0);
        List<Row> subOpt = rows.stream().filter(r -> !r.nearOptimal()).toList();
        double meanSubReg = subOpt.stream().mapToDouble(Row::regret).average().orElse(0);

        System.out.printf("%-14s  exact=%3d/%3d (%4.1f%%)  differ=%3d/%3d  near-opt(<5%%)=%3d/%3d (%4.1f%%)" +
                        "  mean_regret=%5.2f%%  mean_regret_when_subopt=%5.2f%%  max_regret=%5.1f%%%n%n",
                name + ":", exact, total, 100.0 * exact / total,
                differ, total,
                near, total, 100.0 * near / total,
                meanReg * 100, meanSubReg * 100, maxReg * 100);

        // By key mode
        for (boolean wk : new boolean[]{true, false}) {
            List<Row> sub = rows.stream().filter(r -> r.withKeys() == wk).toList();
            long e2 = sub.stream().filter(Row::exactMatch).count();
            long n2 = sub.stream().filter(Row::nearOptimal).count();
            double mr = sub.stream().mapToDouble(Row::regret).average().orElse(0);
            System.out.printf("  %-20s  exact=%3d/%3d (%4.1f%%)  near-opt=%3d/%3d (%4.1f%%)  mean_regret=%5.2f%%%n",
                    wk ? "integer-keyed:" : "comparable-only:",
                    e2, sub.size(), 100.0 * e2 / sub.size(),
                    n2, sub.size(), 100.0 * n2 / sub.size(), mr * 100);
        }
        System.out.println();

        // By shape (all key modes combined)
        System.out.println("  By shape:");
        for (Shape shape : Shape.values()) {
            List<Row> s = rows.stream().filter(r -> r.shape().equals(shape.name().toLowerCase())).toList();
            long e2 = s.stream().filter(Row::exactMatch).count();
            double mr = s.stream().mapToDouble(Row::regret).average().orElse(0);
            System.out.printf("    %-14s  exact=%2d/%2d (%5.1f%%)  mean_regret=%5.2f%%%n",
                    shape.name().toLowerCase() + ":", e2, s.size(),
                    100.0 * e2 / s.size(), mr * 100);
        }
        System.out.println();

        // Top-5 worst regret cases
        if (!subOpt.isEmpty()) {
            System.out.println("  Worst regrets (top 5):");
            subOpt.stream().sorted(Comparator.comparingDouble(Row::regret).reversed())
                    .limit(5)
                    .forEach(r -> System.out.printf(
                            "    n=%6d %-14s keys=%-5s  chosen=%-18s  oracle=%-18s  regret=%6.1f%%%n",
                            r.n(), r.shape(), r.withKeys() ? "yes" : "no",
                            r.chosen(), r.oracle(), r.regret() * 100));
        }
        System.out.println();
    }

    private static void writeCsv(List<Row> rows) throws IOException {
        Path csv = Path.of("build", "reports", "phase4-gate.csv");
        Files.createDirectories(csv.getParent());
        try (var w = Files.newBufferedWriter(csv)) {
            w.write("selector,shape,n,with_keys,chosen,oracle,chosen_cost,oracle_cost,regret\n");
            for (Row r : rows) {
                w.write(String.format("%s,%s,%d,%s,%s,%s,%d,%d,%.6f%n",
                        r.selector(), r.shape(), r.n(), r.withKeys(),
                        r.chosen(), r.oracle(),
                        r.chosenCost(), r.oracleCost(), r.regret()));
            }
        }
        System.out.println("CSV: build/reports/phase4-gate.csv");
    }
}
