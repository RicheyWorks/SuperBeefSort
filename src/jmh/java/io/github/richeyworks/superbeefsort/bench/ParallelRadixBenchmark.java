package io.github.richeyworks.superbeefsort.bench;

import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.strategy.ParallelRadixSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.RadixSortStrategy;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Head-to-head: sequential {@code radix.lsd} vs multi-threaded {@code radix.lsd.parallel}, to locate the
 * <b>crossover</b> — the {@code n} above which the parallel chunked-histogram/scatter beats single-threaded
 * radix by enough to justify routing {@code SMART} to it (evidence-first, the same rule applied to the
 * rejected native kernel).
 *
 * <p><b>De-confounded design (2026-06-23).</b> The first cut declared the two algorithms as two separate
 * {@code @Benchmark} methods, so JMH measured <em>all</em> {@code parallel} sizes first and <em>all</em>
 * {@code sequential} sizes afterwards; any machine drift over the ~2-minute run then aliased directly onto the
 * seq-vs-par gap. The 50k control row exposed it: at {@code n <}
 * {@link ParallelRadixSortStrategy#PARALLEL_THRESHOLD} the parallel strategy runs single-chunk (no threads)
 * yet still read ~13% faster — a pure baseline offset, not a parallel win. This version makes the algorithm a
 * {@code @Param} ({@code strategy}) declared <em>after</em> {@code n} so it varies fastest: for each {@code n},
 * {@code seq} and {@code par} are measured back-to-back in the same JVM, minimising drift between the compared
 * pair. {@code @Fork(3)} (via the Gradle {@code jmh {}} block) averages across fresh JVMs on top of that.
 *
 * <p>{@code n} keeps the 50k <b>control</b> (sub-threshold ⇒ no threads ⇒ measures the residual baseline
 * offset) and extends to 2M/5M so the large-n region — where the ADR's native branch-B only ever showed a win
 * (parity at 1M, +3% at 5M) — is actually covered. The true parallel crossover is the smallest {@code n} where
 * {@code par} beats {@code seq} by <em>more than</em> the 50k baseline offset. Full-range random {@code int}
 * keys (≈8 radix passes), fixed seed; a fresh {@link SortBuffer} is built per invocation (sorting mutates it),
 * so the per-op array copy is a constant common to both arms.
 *
 * <p>Run: {@code gradlew jmh -Pbench=ParallelRadixBenchmark}. Fork/iteration counts come from the Gradle
 * {@code jmh {}} block (currently fork=3), which overrides the annotations here; the annotations record the
 * intended config for a standalone JMH-jar run.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Fork(3)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class ParallelRadixBenchmark {

    /** Declared first ⇒ slowest-varying (outer loop). 50k is the sub-threshold no-threads control. */
    @Param({"50000", "100000", "500000", "1000000", "2000000", "5000000"})
    public int n;

    /**
     * Declared last ⇒ fastest-varying (inner loop), so {@code seq} and {@code par} run back-to-back at each
     * {@code n} — the fix for the run-order confound. {@code "seq"} = {@code radix.lsd},
     * {@code "par"} = {@code radix.lsd.parallel}.
     */
    @Param({"seq", "par"})
    public String strategy;

    private List<Integer> data;
    private SortStrategy<Integer> strat;
    private final Comparator<Integer> cmp = Comparator.naturalOrder();
    private final KeyEncoder<Integer> encoder = KeyEncoder.ofInt(i -> i);

    @Setup(Level.Trial)
    public void setup() {
        strat = "par".equals(strategy) ? new ParallelRadixSortStrategy<>() : new RadixSortStrategy<>();
        Random rng = new Random(42);
        data = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            data.add(rng.nextInt()); // full int range -> ~8 radix passes; the parallel-favourable case
        }
    }

    @Benchmark
    public Integer sort() {
        SortBuffer<Integer> buf = SortBuffer.of(data, cmp, encoder);
        strat.sort(buf, SortContext.noop());
        return buf.get(0);
    }
}
