package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.engine.SortRunResult;
import io.github.richeyworks.superbeefsort.select.CostModelStrategySelector;
import io.github.richeyworks.superbeefsort.select.SelectionPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The {@link BeefSort#maxAuxMemory(long)} facade shortcut: it should install a budgeted
 * {@link CostModelStrategySelector} so a SMART run honours an auxiliary-memory cap end-to-end, degrading
 * to in-place sorts under pressure while leaving an unconstrained run on the cheapest (LINEAR-aux) choice.
 */
class BeefSortBudgetTest {

    /** 50k random bounded integers: SMART would pick the LINEAR-aux counting sort when memory is free. */
    private static List<Integer> boundedInts() {
        Random rnd = new Random(42);
        List<Integer> data = new ArrayList<>(50_000);
        for (int i = 0; i < 50_000; i++) {
            data.add(rnd.nextInt(10_000));
        }
        return data;
    }

    private static String chosenStrategy(long budgetBytes) {
        SortRunResult<Integer> run = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(boundedInts())
                .keyEncoder(KeyEncoder.ofInt(i -> i))
                .policy(SelectionPolicy.SMART)
                .maxAuxMemory(budgetBytes)
                .run();
        return run.plan().strategy().value();
    }

    @Test
    void tightBudgetDegradesToInPlaceSort() {
        // 256 KB cap: counting/radix/learned/TimSort all need 8n = 400 KB > budget, so only introsort fits.
        assertEquals("intro", chosenStrategy(1L << 18));
    }

    @Test
    void generousBudgetStillAllowsLinearCounting() {
        // 1 GB cap: well above 8n = 400 KB, so the budgeted cost model still picks the cheapest LINEAR sort.
        assertEquals("counting", chosenStrategy(1L << 30));
    }

    @Test
    void maxAuxMemoryAndExplicitSelectorAreMutuallyExclusive() {
        BeefSort<Integer> job = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(boundedInts())
                .keyEncoder(KeyEncoder.ofInt(i -> i))
                .maxAuxMemory(1L << 20)
                .selector(new CostModelStrategySelector());
        assertThrows(IllegalStateException.class, job::run);
    }

    @Test
    void nonPositiveBudgetIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> BeefSort.with(Comparator.<Integer>naturalOrder()).maxAuxMemory(0));
    }

    @Test
    void outputIsCorrectlySortedUnderABudget() {
        List<Integer> data = boundedInts();
        SortRunResult<Integer> run = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(data)
                .keyEncoder(KeyEncoder.ofInt(i -> i))
                .policy(SelectionPolicy.SMART)
                .maxAuxMemory(1L << 18)
                .run();
        List<Integer> expected = new ArrayList<>(data);
        expected.sort(Comparator.naturalOrder());
        assertEquals(expected, run.sorted());
    }
}
