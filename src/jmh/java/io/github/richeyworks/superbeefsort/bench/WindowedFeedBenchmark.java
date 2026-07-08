package io.github.richeyworks.superbeefsort.bench;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.ensemble.EnsembleOrderedSet;
import io.github.richeyworks.csrbt.strategy.AVLStrategy;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.superbeefsort.feed.CsrbtTarget;
import io.github.richeyworks.superbeefsort.feed.FeedResult;
import io.github.richeyworks.superbeefsort.feed.StreamingFeeder;
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
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The 2026-07-08 windowed-ensemble seam, priced: a bounded stream (top-N convergence via FIFO
 * eviction) into a single {@code OrderedSet} vs a two-member RB+AVL MIRROR ensemble whose window
 * fans out per member, vs the unbounded single-set baseline. Each invocation feeds a fresh target
 * (allocation included), so the rows answer "what does the K-member mirror's window actually cost
 * per fed run?" — the write fan-out is the known 2x; this measures window bookkeeping on top.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
public class WindowedFeedBenchmark {

    @Param({"100000"})
    public int n;

    @Param({"1024"})
    public int window;

    private List<Integer> sortedDistinct;

    @Setup(Level.Trial)
    public void setup() {
        sortedDistinct = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            sortedDistinct.add(i);
        }
    }

    @Benchmark
    public FeedResult unboundedOrderedSet() {
        OrderedSet<Integer> set = OrderedSet.withNaturalOrder(new RedBlackStrategy<Integer>());
        return new StreamingFeeder<Integer>(0).feed(sortedDistinct, CsrbtTarget.of(set));
    }

    @Benchmark
    public FeedResult windowedOrderedSet() {
        OrderedSet<Integer> set = OrderedSet.withNaturalOrder(new RedBlackStrategy<Integer>());
        return new StreamingFeeder<Integer>(window).feed(sortedDistinct, CsrbtTarget.of(set));
    }

    @Benchmark
    public FeedResult windowedEnsembleMirror() {
        EnsembleOrderedSet<Integer> ens = EnsembleOrderedSet.<Integer>builder(Comparator.<Integer>naturalOrder())
                .member(() -> new RedBlackStrategy<Integer>())
                .member(() -> new AVLStrategy<Integer>())
                .build();
        try {
            return new StreamingFeeder<Integer>(window).feed(sortedDistinct, CsrbtTarget.of(ens));
        } finally {
            ens.close();
        }
    }
}
