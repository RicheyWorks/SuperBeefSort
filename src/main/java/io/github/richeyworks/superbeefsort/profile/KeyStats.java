package io.github.richeyworks.superbeefsort.profile;

/**
 * Integer-key statistics, present only when a faithful {@code KeyEncoder} was supplied. These are
 * what let the selector reach for counting / radix sorts safely.
 */
public record KeyStats(long min, long max, boolean countingFeasible) {

    /** {@code max - min}; may be negative if it overflowed (treated as "too wide" for counting). */
    public long span() {
        return max - min;
    }
}
