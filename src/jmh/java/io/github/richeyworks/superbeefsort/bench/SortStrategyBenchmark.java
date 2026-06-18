package io.github.richeyworks.superbeefsort.bench;

import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.strategy.CountingSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.HeapSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.InPlaceMergeSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.IntroSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.JdkSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.MergeSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.QuickSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.RadixSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.WikiSortStrategy;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Average time per sort of each strategy across three data shapes. Insertion/selection are
 * intentionally excluded — they are O(n^2) on random input and would dominate the run; the selector
 * only reaches for insertion on tiny inputs anyway. Counting/radix consume a {@link KeyEncoder};
 * the comparison sorts ignore it.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
public class SortStrategyBenchmark {

    @Param({"random", "nearlySorted", "fewDistinct"})
    public String shape;

    @Param({"100000"})
    public int n;

    private List<Integer> data;
    private final Comparator<Integer> cmp = Comparator.naturalOrder();
    private final KeyEncoder<Integer> encoder = KeyEncoder.ofInt(i -> i);

    @Setup(Level.Trial)
    public void setup() {
        Random r = new Random(42);
        data = new ArrayList<>(n);
        switch (shape) {
            case "random" -> {
                for (int i = 0; i < n; i++) {
                    data.add(r.nextInt(2 * n));
                }
            }
            case "nearlySorted" -> {
                for (int i = 0; i < n; i++) {
                    data.add(i);
                }
                for (int i = 0; i < n / 100; i++) {
                    Collections.swap(data, r.nextInt(n), r.nextInt(n));
                }
            }
            case "fewDistinct" -> {
                for (int i = 0; i < n; i++) {
                    data.add(r.nextInt(16));
                }
            }
            default -> throw new IllegalStateException("unknown shape: " + shape);
        }
    }

    private Integer sortWith(SortStrategy<Integer> strategy, boolean withEncoder) {
        SortBuffer<Integer> buffer = withEncoder
                ? SortBuffer.of(data, cmp, encoder)
                : SortBuffer.of(data, cmp);
        strategy.sort(buffer, SortContext.noop());
        return buffer.get(0); // returned (the minimum) so JMH can't eliminate the sort
    }

    @Benchmark public Integer introSort()    { return sortWith(new IntroSortStrategy<>(), false); }
    @Benchmark public Integer quickSort()     { return sortWith(new QuickSortStrategy<>(), false); }
    @Benchmark public Integer mergeSort()     { return sortWith(new MergeSortStrategy<>(), false); }
    @Benchmark public Integer inPlaceMergeSort() { return sortWith(new InPlaceMergeSortStrategy<>(), false); }
    @Benchmark public Integer wikiSort()      { return sortWith(new WikiSortStrategy<>(), false); }
    @Benchmark public Integer heapSort()      { return sortWith(new HeapSortStrategy<>(), false); }
    @Benchmark public Integer jdkSort()       { return sortWith(new JdkSortStrategy<>(), false); }
    @Benchmark public Integer countingSort()  { return sortWith(new CountingSortStrategy<>(), true); }
    @Benchmark public Integer radixSort()     { return sortWith(new RadixSortStrategy<>(), true); }
}
