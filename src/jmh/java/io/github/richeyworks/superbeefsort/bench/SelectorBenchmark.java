package io.github.richeyworks.superbeefsort.bench;

import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.engine.BeefSortEngine;
import io.github.richeyworks.superbeefsort.engine.JobSpec;
import io.github.richeyworks.superbeefsort.engine.SortRunResult;
import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.profile.IntelligentDataProfiler;
import io.github.richeyworks.superbeefsort.profile.ProfileDepth;
import io.github.richeyworks.superbeefsort.registry.StrategyRegistry;
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Phase 4 ADR action item 5: benchmark the learned selector <em>in the pipeline</em>, in-JVM (ADR
 * {@code docs/adr-phase4-python-intelligence.md}).
 *
 * <p>Two benchmark groups, parameterised by input size and shape. (Raw select-only latency —
 * cost-model vs bandit vs learned on a pre-built profile — lives in
 * {@link SelectorInferenceLatencyBenchmark}; this class measures the cost of selection as part of the
 * profile→select→sort pipeline, where it must be amortized.)</p>
 * <ol>
 *   <li><b>Profile + select</b> ({@code profileAndSelectCostModel}, {@code profileAndSelectLearned}):
 *       creates a fresh {@link SortBuffer} and runs the O(n) profiler then the selector. Shows the
 *       learned model's marginal overhead relative to the always-paid profiling cost.
 *       Reported in <b>nanoseconds</b> (microsecond range at large n).</li>
 *   <li><b>Full sort</b> ({@code sortWithCostModel}, {@code sortWithLearned}): runs
 *       {@link BeefSortEngine#sort BeefSortEngine.sort()} end-to-end — profile → select → sort.
 *       Shows whether the learned selector's better pick (98.1% exact-match vs 60.5% for the cost
 *       model against the oracle) more than covers the inference overhead. Reported in
 *       <b>milliseconds</b>.</li>
 * </ol>
 *
 * <p>Run: {@code ./gradlew jmh -Pbench=SelectorBenchmark}</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class SelectorBenchmark {

    @Param({"512", "10000", "100000"})
    public int n;

    /** {@code randomInts} and {@code nearlySorted} carry integer key stats (counting/radix eligible);
     *  {@code randomComparable} has no key encoder, so only comparison sorts are considered. */
    @Param({"randomInts", "nearlySorted", "randomComparable"})
    public String shape;

    private StrategyRegistry registry;
    private CostModelStrategySelector costModel;
    private LearnedModelStrategySelector learned;

    // source data and helpers reused by both benchmark groups
    private List<Integer> data;
    private boolean intKeys;
    private final Comparator<Integer> cmp = Comparator.naturalOrder();
    private final KeyEncoder<Integer> encoder = KeyEncoder.ofInt(i -> i);
    private final IntelligentDataProfiler<Integer> profiler = new IntelligentDataProfiler<>();

    // engine instances for the full-sort benchmarks
    private BeefSortEngine<Integer> costModelEngine;
    private BeefSortEngine<Integer> learnedEngine;
    private final JobSpec spec = JobSpec.defaults();

    @Setup(Level.Trial)
    public void setup() {
        intKeys = !shape.equals("randomComparable");
        Random rng = new Random(42);
        data = new ArrayList<>(n);
        switch (shape) {
            case "randomInts", "randomComparable" -> {
                for (int i = 0; i < n; i++) data.add(rng.nextInt(2 * n));
            }
            case "nearlySorted" -> {
                for (int i = 0; i < n; i++) data.add(i);
                for (int i = 0; i < n / 100; i++) Collections.swap(data, rng.nextInt(n), rng.nextInt(n));
            }
            default -> throw new IllegalStateException("unknown shape: " + shape);
        }

        registry = StrategyRegistry.withDefaults();
        costModel = new CostModelStrategySelector();

        // Load the exported decision tree; the path resolves relative to the Gradle project root
        // when JMH is launched via ./gradlew jmh.
        SelectorModel model = SelectorModel.load(Path.of("tools/phase4/sbs_selector_model.txt"));
        learned = new LearnedModelStrategySelector(model);

        KeyEncoder<Integer> ke = intKeys ? encoder : null;
        costModelEngine = new BeefSortEngine<>(costModel, ke);
        learnedEngine   = new BeefSortEngine<>(learned,    ke);
    }

    // ---- group 1: profile + select (O(n) profiling + inference, ns→µs range) ---- //

    /**
     * Creates a fresh {@link SortBuffer} and runs the O(n) profiler then the cost-model selector.
     * The profiling cost is always paid; comparing this against {@link #profileAndSelectLearned}
     * shows the learned model's marginal overhead as a fraction of the profiling work.
     */
    @Benchmark
    public SortPlan profileAndSelectCostModel() {
        SortBuffer<Integer> buf = intKeys
                ? SortBuffer.of(data, cmp, encoder)
                : SortBuffer.of(data, cmp);
        DataProfile p = profiler.profile(buf, ProfileDepth.SHALLOW);
        return costModel.select(p, SelectionPolicy.SMART, registry);
    }

    /** Profile + learned-model select: total cost of "decide what strategy to use." */
    @Benchmark
    public SortPlan profileAndSelectLearned() {
        SortBuffer<Integer> buf = intKeys
                ? SortBuffer.of(data, cmp, encoder)
                : SortBuffer.of(data, cmp);
        DataProfile p = profiler.profile(buf, ProfileDepth.SHALLOW);
        return learned.select(p, SelectionPolicy.SMART, registry);
    }

    // ---- group 2: full engine — profile + select + sort (ms range) ---- //

    /**
     * Full {@link BeefSortEngine} pipeline with the cost-model selector.
     * {@code data} is copied internally (via {@link SortBuffer}), so the benchmark is idempotent
     * across iterations.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public Integer sortWithCostModel() {
        SortRunResult<Integer> r = costModelEngine.sort(data, cmp, spec);
        return r.sorted().get(0); // consume first element so JMH can't eliminate the sort
    }

    /**
     * Full {@link BeefSortEngine} pipeline with the learned-model selector. A better strategy pick
     * (e.g. counting over introsort on random-integer data) pays back the inference overhead many
     * times over at any n where the sort dominates the microsecond-class selection cost.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public Integer sortWithLearned() {
        SortRunResult<Integer> r = learnedEngine.sort(data, cmp, spec);
        return r.sorted().get(0);
    }
}
