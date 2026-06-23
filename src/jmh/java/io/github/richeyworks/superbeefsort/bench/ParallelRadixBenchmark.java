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
 * Head-to-head: sequential {@code radix.lsd} vs multi-threaded {@code radix.lsd.parallel} across input
 * sizes that straddle the parallel engage threshold (1&lt;&lt;16). Its purpose is to locate the
 * <b>crossover</b> — the {@code n} above which the parallel chunked-histogram/scatter beats the single-threaded
 * radix by enough to justify routing {@code SMART} to it — so selector integration is made on measured data,
 * the same evidence-first rule applied to the (rejected) native kernel.
 *
 * <p>Below the threshold {@code radix.lsd.parallel} delegates to a single-threaded pass identical to
 * {@code radix.lsd}, so those rows should read as a tie (any gap is pure overhead and must be ~0). Above it,
 * the gap is the multicore win. Full-range random {@code int} keys (≈8 radix passes), fixed seed.</p>
 *
 * <p>Run: {@code ./gradlew jmh -Pbench=ParallelRadixBenchmark}. Bounded forks/iterations keep the whole sweep
 * to a few minutes; raise them for a publication-grade number once the crossover region is known.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 4, time = 2)
public class ParallelRadixBenchmark {

    @Param({"50000", "100000", "500000", "1000000"})
    public int n;

    private List<Integer> data;
    private final Comparator<Integer> cmp = Comparator.naturalOrder();
    private final KeyEncoder<Integer> encoder = KeyEncoder.ofInt(i -> i);

    private final SortStrategy<Integer> sequential = new RadixSortStrategy<>();
    private final SortStrategy<Integer> parallel = new ParallelRadixSortStrategy<>();

    @Setup(Level.Trial)
    public void setup() {
        Random rng = new Random(42);
        data = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            data.add(rng.nextInt()); // full int range -> ~8 radix passes; the parallel-favourable case
        }
    }

    @Benchmark
    public Integer sequentialRadixLsd() {
        SortBuffer<Integer> buf = SortBuffer.of(data, cmp, encoder);
        sequential.sort(buf, SortContext.noop());
        return buf.get(0);
    }

    @Benchmark
    public Integer parallelRadixLsd() {
        SortBuffer<Integer> buf = SortBuffer.of(data, cmp, encoder);
        parallel.sort(buf, SortContext.noop());
        return buf.get(0);
    }
}
