package io.github.richeyworks.superbeefsort.bench;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.superbeefsort.feed.BalancedBuildFeeder;
import io.github.richeyworks.superbeefsort.feed.BulkBuildFeeder;
import io.github.richeyworks.superbeefsort.feed.CsrbtTarget;
import io.github.richeyworks.superbeefsort.feed.FeedResult;
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
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The payoff benchmark: feeding an already-sorted, distinct run into a fresh CSRBT {@code OrderedSet}
 * via the O(n) bulk build vs the median-first balanced {@code add()} path. Each invocation builds a
 * fresh set, so the comparison is apples-to-apples (allocation included).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
public class FeedBenchmark {

    @Param({"100000"})
    public int n;

    private List<Integer> sortedDistinct;

    @Setup(Level.Trial)
    public void setup() {
        sortedDistinct = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            sortedDistinct.add(i);
        }
    }

    @Benchmark
    public FeedResult bulkBuild() {
        OrderedSet<Integer> set = OrderedSet.withNaturalOrder(new RedBlackStrategy<Integer>());
        return new BulkBuildFeeder<Integer>().feed(sortedDistinct, CsrbtTarget.of(set));
    }

    @Benchmark
    public FeedResult balancedAdd() {
        OrderedSet<Integer> set = OrderedSet.withNaturalOrder(new RedBlackStrategy<Integer>());
        return new BalancedBuildFeeder<Integer>().feed(sortedDistinct, CsrbtTarget.of(set));
    }
}
