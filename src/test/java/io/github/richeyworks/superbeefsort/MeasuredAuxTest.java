package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.engine.SortRunResult;
import io.github.richeyworks.superbeefsort.select.SelectionPolicy;
import io.github.richeyworks.superbeefsort.strategy.HeapSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.MergeSortStrategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measured peak auxiliary memory: strategies self-report scratch via {@link SortBuffer#recordAux}, so
 * {@link SortBuffer#peakAuxBytes()} (and {@code SortResult.peakAuxBytes}) carries a real, per-strategy
 * memory signal — finer than the static {@code StrategyCapabilities.AuxMemory} class.
 */
class MeasuredAuxTest {

    private static List<Integer> data(int n) {
        Random r = new Random(1);
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            a.add(r.nextInt());
        }
        return a;
    }

    @Test
    void mergeReportsLinearAux() {
        int n = 1000;
        SortBuffer<Integer> b = SortBuffer.of(data(n), Comparator.<Integer>naturalOrder());
        new MergeSortStrategy<Integer>().sort(b, SortContext.noop());
        // working copy (8 B/ref) + merge scratch (8 B/ref) = 16 B/element
        assertEquals(16L * n, b.peakAuxBytes());
    }

    @Test
    void inPlaceSortReportsZeroAux() {
        int n = 1000;
        SortBuffer<Integer> b = SortBuffer.of(data(n), Comparator.<Integer>naturalOrder());
        new HeapSortStrategy<Integer>().sort(b, SortContext.noop());
        assertEquals(0L, b.peakAuxBytes(), "an in-place sort allocates no scratch");
    }

    @Test
    void surfacesThroughSortResult() {
        // measured aux flows SortBuffer -> SortRunResult.sortMetrics().peakAuxBytes(); STABLE -> merge sort
        SortRunResult<Integer> run = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(data(2000))
                .policy(SelectionPolicy.STABLE)
                .run();
        assertEquals("merge", run.plan().strategy().value());
        assertEquals(16L * 2000, run.sortMetrics().peakAuxBytes());
    }
}
