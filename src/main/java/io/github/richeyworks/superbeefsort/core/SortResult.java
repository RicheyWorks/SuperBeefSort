package io.github.richeyworks.superbeefsort.core;

/**
 * Outcome and metrics of a single {@link SortStrategy} run. {@code peakAuxBytes} is the measured peak
 * auxiliary (scratch) allocation the strategy reported — 0 for in-place sorts — finer than the static
 * {@code StrategyCapabilities.AuxMemory} estimate, so a learning selector can tune on real memory cost.
 */
public record SortResult(StrategyId strategyId, int size, long comparisons, long moves, long elapsedNanos,
                         long peakAuxBytes) {

    public double elapsedMillis() {
        return elapsedNanos / 1_000_000.0;
    }
}
