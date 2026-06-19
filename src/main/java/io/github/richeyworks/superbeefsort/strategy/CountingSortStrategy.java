package io.github.richeyworks.superbeefsort.strategy;

import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.core.StrategyCapabilities;
import io.github.richeyworks.superbeefsort.core.StrategyId;

/**
 * Stable counting sort over an integer key range. Linear time when the range is small relative to
 * {@code n} — the selector only picks it when the profiler reports a bounded, faithful key range.
 * Requires a {@link KeyEncoder} on the buffer.
 */
public final class CountingSortStrategy<K> implements SortStrategy<K> {

    public static final StrategyId ID = StrategyId.of("counting");
    private static final long MAX_RANGE = 1L << 24;

    @Override
    @SuppressWarnings("unchecked")
    public void sort(SortBuffer<K> b, SortContext context) {
        int n = b.size();
        if (n < 2) {
            return;
        }
        KeyEncoder<K> encoder = b.keyEncoder();
        if (encoder == null) {
            throw new IllegalStateException("CountingSortStrategy requires a KeyEncoder on the buffer");
        }

        long[] keys = new long[n];
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            long k = encoder.encodeKey(b.get(i));
            keys[i] = k;
            if (k < min) {
                min = k;
            }
            if (k > max) {
                max = k;
            }
        }
        long span = max - min;
        if (span < 0 || span >= MAX_RANGE) {
            throw new IllegalStateException("CountingSortStrategy key range too large: " + span);
        }

        int range = (int) (span + 1);
        int[] count = new int[range];
        for (int i = 0; i < n; i++) {
            count[(int) (keys[i] - min)]++;
        }
        // Convert counts to start indices (exclusive prefix sum) -> stable placement.
        int acc = 0;
        for (int v = 0; v < range; v++) {
            int c = count[v];
            count[v] = acc;
            acc += c;
        }
        Object[] out = new Object[n];
        for (int i = 0; i < n; i++) {
            int v = (int) (keys[i] - min);
            out[count[v]++] = b.get(i);
        }
        for (int i = 0; i < n; i++) {
            b.set(i, (K) out[i]);
            b.recordMove();
        }
    }

    @Override
    public StrategyCapabilities capabilities() {
        return StrategyCapabilities.builder()
                .stable(true).inPlace(false).comparisonBased(false).requiresIntegerKeys(true)
                .auxMemory(StrategyCapabilities.AuxMemory.LINEAR).build();   // O(n + range) count/output arrays
    }

    @Override
    public StrategyId id() {
        return ID;
    }
}
