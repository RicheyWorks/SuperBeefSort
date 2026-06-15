package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.engine.SortReport;
import io.github.richeyworks.superbeefsort.engine.SortRunResult;
import io.github.richeyworks.superbeefsort.select.SelectionPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SortReport: a flat dashboard built from a SortRunResult (architecture sec 5.7). */
class SortReportTest {

    @Test
    void summarizesASortAndFeedJob() {
        OrderedSet<Integer> set = OrderedSet.withNaturalOrder(new RedBlackStrategy<Integer>());
        SortRunResult<Integer> run = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(new ArrayList<>(List.of(9, 3, 7, 1, 8, 2, 5, 3))) // 8 items, one duplicate (3)
                .keyEncoder(KeyEncoder.ofInt(i -> i))
                .policy(SelectionPolicy.SMART)
                .feedInto(set);

        SortReport report = SortReport.of(run);

        assertTrue(report.fed());
        assertEquals(run.sortMetrics().strategyId(), report.strategy());
        assertEquals(8, report.size());
        assertEquals(run.feedResult().mode(), report.feedMode());
        assertEquals(7, report.inserted(), "seven distinct keys");
        assertEquals(1, report.duplicates(), "one duplicate (the repeated 3)");
        assertTrue(report.healthy());
        assertTrue(report.throughputItemsPerSec() >= 0.0);
        assertEquals(report.sortMillis() + report.feedMillis(), report.totalMillis(), 1e-9);
        assertTrue(report.summary().contains("items/s"), "summary was: " + report.summary());
    }

    @Test
    void summarizesASortOnlyJob() {
        SortRunResult<Integer> run = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(new ArrayList<>(List.of(5, 2, 8, 1)))
                .run(); // sort only, no feed

        SortReport report = SortReport.of(run);

        assertFalse(report.fed());
        assertNull(report.feedMode());
        assertEquals(4, report.size());
        assertEquals(0, report.inserted());
        assertEquals(0.0, report.feedMillis(), 1e-9);
    }
}
