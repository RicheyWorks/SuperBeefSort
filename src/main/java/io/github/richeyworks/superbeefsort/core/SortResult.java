package io.github.richeyworks.superbeefsort.core;

/** Outcome and metrics of a single {@link SortStrategy} run. */
public record SortResult(StrategyId strategyId, int size, long comparisons, long moves, long elapsedNanos) {

    public double elapsedMillis() {
        return elapsedNanos / 1_000_000.0;
    }
}
