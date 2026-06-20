package io.github.richeyworks.superbeefsort.engine;

import io.github.richeyworks.superbeefsort.core.SortResult;
import io.github.richeyworks.superbeefsort.core.StrategyId;
import io.github.richeyworks.superbeefsort.feed.FeedMode;
import io.github.richeyworks.superbeefsort.feed.FeedResult;

/**
 * A flat, human-readable summary of one sort-and-feed job -- the cross-domain dashboard from the
 * architecture (sec 5.7): what ran, how much work it took, how it fed CSRBT, and the resulting
 * end-to-end throughput. Built from a {@link SortRunResult}; the feed fields are absent/zero for a
 * sort-only job ({@link #fed()} is false).
 */
public record SortReport(
        StrategyId strategy,
        int size,
        long comparisons,
        long moves,
        long peakAuxBytes,
        double sortMillis,
        boolean fed,
        FeedMode feedMode,   // null for a sort-only job
        int inserted,
        int duplicates,
        boolean healthy,
        double feedMillis) {

    /** End-to-end items/second over sort + feed wall time (0 if no measurable time elapsed). */
    public double throughputItemsPerSec() {
        double seconds = (sortMillis + feedMillis) / 1_000.0;
        return seconds > 0 ? size / seconds : 0.0;
    }

    /** Total sort + feed wall time in milliseconds. */
    public double totalMillis() {
        return sortMillis + feedMillis;
    }

    /** Build a report from a completed run. Feed fields are zero/absent when the job was sort-only. */
    public static <K> SortReport of(SortRunResult<K> run) {
        SortResult m = run.sortMetrics();
        if (!run.wasFed()) {
            return new SortReport(m.strategyId(), m.size(), m.comparisons(), m.moves(), m.peakAuxBytes(), m.elapsedMillis(),
                    false, null, 0, 0, true, 0.0);
        }
        FeedResult f = run.feedResult();
        return new SortReport(m.strategyId(), m.size(), m.comparisons(), m.moves(), m.peakAuxBytes(), m.elapsedMillis(),
                true, f.mode(), f.inserted(), f.duplicates(), f.healthy(), f.elapsedMillis());
    }

    /** One-line summary suitable for logs or an observer. */
    public String summary() {
        StringBuilder sb = new StringBuilder()
                .append(strategy.value())
                .append("  n=").append(size)
                .append("  ").append(comparisons).append(" cmp")
                .append("  ").append(moves).append(" mv")
                .append("  ").append(peakAuxBytes).append(" auxB")
                .append(String.format("  %.2f ms sort", sortMillis));
        if (fed) {
            sb.append("  ->  ").append(feedMode)
                    .append("  ").append(inserted).append(" inserted/").append(duplicates).append(" dup")
                    .append("  healthy=").append(healthy)
                    .append(String.format("  %.2f ms feed", feedMillis));
        }
        sb.append(String.format("  |  %.0f items/s", throughputItemsPerSec()));
        return sb.toString();
    }
}
