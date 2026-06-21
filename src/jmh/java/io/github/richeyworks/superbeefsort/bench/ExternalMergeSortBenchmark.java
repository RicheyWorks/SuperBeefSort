package io.github.richeyworks.superbeefsort.bench;

import io.github.richeyworks.superbeefsort.BeefSort;
import io.github.richeyworks.superbeefsort.external.SpillSerializer;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Establish the IO-dominated cost curve for external merge sort as runSize and fanIn vary
 * at a fixed total N=100k. The goal is measurement, not tuning — defaults stay as-is until
 * a follow-on session reviews the numbers.
 *
 * <p>Pass-count reference (n=100k):
 * <pre>
 *   runSize=1000 (100 runs) : fanIn=4→4p  fanIn=16→2p  fanIn=64→2p
 *   runSize=5000 ( 20 runs) : fanIn=4→3p  fanIn=16→2p  fanIn=64→1p
 *   runSize=25000 ( 4 runs) : fanIn=4→1p  fanIn=16→1p  fanIn=64→1p
 * </pre>
 * ("Xp" = X merge passes including the final merge)
 *
 * <p>Run: {@code ./gradlew jmh -Pbench=ExternalMergeSortBenchmark}
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
public class ExternalMergeSortBenchmark {

    /** Fixed total element count — large enough to need multiple passes at small runSize. */
    @Param({"100000"})
    public int n;

    /**
     * Chunk size per spill run. Drives run count (n/runSize) and therefore pass depth.
     * 1000 → 100 runs (multi-pass at any fanIn); 25000 → 4 runs (single-pass).
     */
    @Param({"1000", "5000", "25000"})
    public int runSize;

    /** k-way fan-in at each merge pass. Higher fan-in means fewer passes, more concurrent IO. */
    @Param({"4", "16", "64"})
    public int fanIn;

    private List<Integer> data;

    /**
     * Pass count is deterministic from (n, runSize, fanIn). Pre-computed so the benchmark
     * method can feed it into the AuxCounters without repeating the arithmetic each iteration.
     */
    private int passesPerOp;

    /**
     * Auxiliary metric: merge passes per sort operation. Because {@code passesPerOp} is constant
     * for a given (runSize, fanIn) combination, this always reports the exact pass count —
     * making it easy to correlate latency with the number of full data scans.
     */
    @State(Scope.Thread)
    @AuxCounters(AuxCounters.Type.EVENTS)
    public static class PassCounters {
        public long mergePasses;
    }

    @Setup(Level.Trial)
    public void setup() {
        Random r = new Random(42);
        data = new ArrayList<>(n);
        for (int i = 0; i < n; i++) data.add(r.nextInt(2 * n));
        int numRuns = (n + runSize - 1) / runSize;
        passesPerOp = countPasses(numRuns);
    }

    @Benchmark
    public List<Integer> externalSort(PassCounters pc) throws IOException {
        pc.mergePasses += passesPerOp;
        return BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(data)
                .external(SpillSerializer.forIntegers())
                .runSize(runSize)
                .fanIn(fanIn)
                .toList();
    }

    private int countPasses(int numRuns) {
        if (numRuns <= 1) return 1;
        int passes = 1, cur = numRuns;
        while (cur > fanIn) {
            cur = (cur + fanIn - 1) / fanIn;
            passes++;
        }
        return passes;
    }
}
