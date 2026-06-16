package io.github.richeyworks.superbeefsort.bench;

import io.github.richeyworks.csrbt.ensemble.EnsembleOrderedSet;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.superbeefsort.feed.BalancedBuildFeeder;
import io.github.richeyworks.superbeefsort.feed.CsrbtTarget;
import io.github.richeyworks.superbeefsort.feed.FeedResult;
import io.github.richeyworks.superbeefsort.feed.ParallelFeeder;
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
 * Phase 3 payoff: feeding a sorted, distinct run into an N-version (mirror) ensemble of red-black members.
 * Compares median-first {@code add()} into a sequential vs a {@code parallelFanOut()} ensemble, and the
 * O(n)/member {@code buildAllFromSorted} fast path that {@link ParallelFeeder} takes on an empty parallel
 * ensemble. Each invocation builds a fresh ensemble, so allocation is included apples-to-apples.
 *
 * <p>Indicative result (n=100k, 3 members, quick run): {@code parallelBulkBuild} ~3.6 ms/op,
 * {@code sequentialMedianAdd} ~133 ms/op, {@code parallelMedianAdd} ~4900 ms/op. The bulk path wins by
 * ~36x; per-{@code add} parallel fan-out is a <em>pessimization</em> because the per-add thread-handoff
 * cost swamps 100k tiny inserts — which is why {@code ParallelFeeder} prefers the bulk path.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
public class EnsembleFeedBenchmark {

    @Param({"100000"})
    public int n;

    @Param({"3"})
    public int members;

    private List<Integer> sortedDistinct;

    @Setup(Level.Trial)
    public void setup() {
        sortedDistinct = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            sortedDistinct.add(i);
        }
    }

    private EnsembleOrderedSet<Integer> freshEnsemble(boolean parallel) {
        var b = EnsembleOrderedSet.builder(Comparator.<Integer>naturalOrder());
        for (int i = 0; i < members; i++) {
            b = b.member(() -> new RedBlackStrategy<Integer>());
        }
        if (parallel) {
            b = b.parallelFanOut();
        }
        return b.build();
    }

    /** Median-first add, fanned out to members sequentially (E1). */
    @Benchmark
    public FeedResult sequentialMedianAdd() {
        return new BalancedBuildFeeder<Integer>().feed(sortedDistinct, CsrbtTarget.of(freshEnsemble(false)));
    }

    /** Median-first add, fanned out to members in parallel (E5) — slow: per-add thread handoff dominates. */
    @Benchmark
    public FeedResult parallelMedianAdd() {
        return new BalancedBuildFeeder<Integer>().feed(sortedDistinct, CsrbtTarget.of(freshEnsemble(true)));
    }

    /** O(n)/member buildAllFromSorted, fanned out in parallel (the Phase 3 fast path). */
    @Benchmark
    public FeedResult parallelBulkBuild() {
        return new ParallelFeeder<Integer>().feed(sortedDistinct, CsrbtTarget.of(freshEnsemble(true)));
    }
}
