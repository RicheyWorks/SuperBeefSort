package io.github.richeyworks.superbeefsort.external;

/**
 * Summary metrics for a completed external merge sort.
 *
 * @param elements     Total number of elements sorted.
 * @param runs         Number of sorted runs generated (= ⌈elements / runSize⌉).
 * @param mergePasses  Total merge passes, including the final one (1 = single-pass: all runs merged
 *                     directly; 2+ = multi-pass because {@code runs > fanIn}).
 * @param elapsedNanos Wall-clock time from start to end of the sort (run generation + all merges).
 */
public record ExternalSortResult(int elements, int runs, int mergePasses, long elapsedNanos) {

    public double elapsedMillis() {
        return elapsedNanos / 1_000_000.0;
    }
}
