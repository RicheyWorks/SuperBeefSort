package io.github.richeyworks.superbeefsort.bench;

import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.profile.IntelligentDataProfiler;
import io.github.richeyworks.superbeefsort.profile.ProfileDepth;
import io.github.richeyworks.superbeefsort.registry.StrategyRegistry;
import io.github.richeyworks.superbeefsort.select.BanditStrategySelector;
import io.github.richeyworks.superbeefsort.select.CostModelStrategySelector;
import io.github.richeyworks.superbeefsort.select.LearnedModelStrategySelector;
import io.github.richeyworks.superbeefsort.select.SelectionPolicy;
import io.github.richeyworks.superbeefsort.select.SelectorModel;
import io.github.richeyworks.superbeefsort.select.SortPlan;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Phase 4 — ADR action item 5: the <em>per-job selection latency</em> of
 * {@link LearnedModelStrategySelector} versus its {@link CostModelStrategySelector} delegate and the
 * {@link BanditStrategySelector}. The data is profiled once in {@link #setup()}, so each benchmark
 * times <em>only</em> {@code select(...)} — the cost-model's analytic scan, the bandit's UCB scan, or
 * the learned selector's added work: extracting the {@link DataProfile} feature vector + walking the
 * exported decision tree (the model is the depth-5 / 23-node tree trained in items 3-4).
 *
 * <p><b>Why this closes the item.</b> Selection <em>quality</em> is already proven (the gate:
 * learned 98.1% exact / 0.50% regret vs the bandit's 65.4% / 191.94%). The only open question is
 * whether the learned selector's inference is cheap enough to be free in practice. It runs <em>once
 * per sort job</em>, so the relevant comparison is its select() overhead (nanoseconds) against the
 * sort it then drives (microseconds-to-milliseconds, see {@link SortStrategyBenchmark}). The
 * {@code n=below-gate} parameter also measures the {@code size < sizeGate} short-circuit, where the
 * learned selector returns the delegate's plan without touching the model.</p>
 *
 * <p>Run just this suite: {@code ./gradlew jmh -Pbench=SelectorInferenceLatencyBenchmark}.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class SelectorInferenceLatencyBenchmark {

    /** Routing-distinct inputs: clustered integer keys (=> counting), random/sorted comparable. */
    @Param({"int_clustered", "comparable_random", "comparable_sorted"})
    public String shape;

    /** 200 is below the default 256 size gate (short-circuit); 100000 is the steady-state full walk. */
    @Param({"200", "100000"})
    public int n;

    private final Comparator<Integer> cmp = Comparator.naturalOrder();
    private final KeyEncoder<Integer> encoder = KeyEncoder.ofInt(i -> i);
    private final StrategyRegistry registry = StrategyRegistry.withDefaults();
    private final IntelligentDataProfiler<Integer> profiler = new IntelligentDataProfiler<>();

    private DataProfile profile;
    private CostModelStrategySelector costModel;
    private BanditStrategySelector bandit;
    private LearnedModelStrategySelector learned;

    @Setup(Level.Trial)
    public void setup() {
        Random r = new Random(42);
        List<Integer> data = new ArrayList<>(n);
        boolean withEncoder;
        switch (shape) {
            case "int_clustered" -> {
                int clusters = Math.max(4, n / 20);
                for (int i = 0; i < n; i++) {
                    data.add(r.nextInt(clusters) * 100 + r.nextInt(10));
                }
                withEncoder = true;
            }
            case "comparable_random" -> {
                for (int i = 0; i < n; i++) {
                    data.add(r.nextInt(2 * n));
                }
                withEncoder = false;
            }
            case "comparable_sorted" -> {
                for (int i = 0; i < n; i++) {
                    data.add(i);
                }
                withEncoder = false;
            }
            default -> throw new IllegalStateException("unknown shape: " + shape);
        }

        SortBuffer<Integer> buffer = withEncoder
                ? SortBuffer.of(data, cmp, encoder)
                : SortBuffer.of(data, cmp);
        // Profile ONCE here — not timed. Each benchmark below times only select(...).
        profile = profiler.profile(buffer, ProfileDepth.SHALLOW);

        costModel = new CostModelStrategySelector();
        bandit = new BanditStrategySelector();
        learned = new LearnedModelStrategySelector(loadModel());
    }

    /** Use the committed model when present (tracks retrains); else the embedded copy keeps the bench self-contained. */
    private static SelectorModel loadModel() {
        Path p = Path.of("tools", "phase4", "sbs_selector_model.txt");
        if (Files.exists(p)) {
            return SelectorModel.load(p);
        }
        return SelectorModel.parse(EMBEDDED_MODEL);
    }

    @Benchmark
    public SortPlan costModelSelect() {
        return costModel.select(profile, SelectionPolicy.SMART, registry);
    }

    @Benchmark
    public SortPlan banditSelect() {
        return bandit.select(profile, SelectionPolicy.SMART, registry);
    }

    @Benchmark
    public SortPlan learnedSelect() {
        return learned.select(profile, SelectionPolicy.SMART, registry);
    }

    // The depth-5 / 23-node model from items 3-4 (tools/phase4/sbs_selector_model.txt), embedded as a
    // fallback so the benchmark is self-contained even off the project root. Kept in sync by copy.
    private static final String EMBEDDED_MODEL = String.join("\n",
            "sbs-selector-model 1 decision_tree",
            "features size,sortedness_ratio,has_duplicates,distinct_estimate,distinct_ratio,has_key_stats,"
                    + "key_span,counting_feasible,distribution_ord,longest_run,longest_run_ratio,inversions,"
                    + "inversion_ratio,inversions_exact,has_byte_key",
            "classes counting,insertion,intro,jdk.timsort",
            "n_nodes 23",
            "feature 6 11 0 -1 2 -1 8 -1 -1 4 12 -1 -1 3 1 -1 -1 -1 12 13 -1 -1 -1",
            "threshold 1.500000 119.000000 9000.000000 0.000000 0.500000 0.000000 1.500000 0.000000 0.000000 "
                    + "0.017812 0.436890 0.000000 0.000000 6.000000 0.758906 0.000000 0.000000 0.000000 0.011161 "
                    + "0.500000 0.000000 0.000000 0.000000",
            "left 1 2 3 -1 5 -1 7 -1 -1 10 11 -1 -1 14 15 -1 -1 -1 19 20 -1 -1 -1",
            "right 18 9 4 -1 6 -1 8 -1 -1 13 12 -1 -1 17 16 -1 -1 -1 22 21 -1 -1 -1",
            "class 0 3 1 1 0 3 0 2 0 3 2 2 3 3 2 2 3 3 0 1 0 1 0",
            "confidence 0.401235 0.583333 0.890909 1.000000 0.333333 1.000000 0.500000 1.000000 1.000000 "
                    + "0.824000 0.833333 1.000000 1.000000 0.980198 0.500000 1.000000 1.000000 1.000000 0.888889 "
                    + "0.888889 1.000000 1.000000 1.000000");
}
