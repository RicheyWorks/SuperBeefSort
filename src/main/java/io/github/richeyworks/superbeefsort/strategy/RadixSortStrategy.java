package io.github.richeyworks.superbeefsort.strategy;

import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.core.StrategyCapabilities;
import io.github.richeyworks.superbeefsort.core.StrategyId;

import java.util.Arrays;

/**
 * Stable least-significant-digit radix sort over 64-bit integer keys, 8 bits per pass. The encoded
 * key's sign bit is flipped so signed values sort correctly under unsigned digit ordering, and only
 * the bytes actually populated by the maximum key are processed. Requires a {@link KeyEncoder}.
 */
public final class RadixSortStrategy<K> implements SortStrategy<K> {

    public static final StrategyId ID = StrategyId.of("radix.lsd");
    private static final int BITS = 8;
    private static final int RADIX = 1 << BITS;
    private static final int MASK = RADIX - 1;

    @Override
    @SuppressWarnings("unchecked")
    public void sort(SortBuffer<K> b, SortContext context) {
        int n = b.size();
        if (n < 2) {
            return;
        }
        KeyEncoder<K> encoder = b.keyEncoder();
        if (encoder == null) {
            throw new IllegalStateException("RadixSortStrategy requires a KeyEncoder on the buffer");
        }

        long[] keys = new long[n];
        Object[] items = new Object[n];
        long maxU = 0L;
        for (int i = 0; i < n; i++) {
            long u = encoder.encodeKey(b.get(i)) ^ Long.MIN_VALUE; // signed -> unsigned ordering
            keys[i] = u;
            items[i] = b.get(i);
            // Unsigned compare: after the sign flip, all-non-negative keys have the top bit set
            // (negative as a signed long), so a signed `>` would leave maxU at 0 and truncate the
            // pass count to a single byte. Long.compareUnsigned keeps the true high byte.
            if (Long.compareUnsigned(u, maxU) > 0) {
                maxU = u;
            }
        }

        long[] tmpKeys = new long[n];
        Object[] tmpItems = new Object[n];
        int[] count = new int[RADIX + 1];

        for (int shift = 0; shift < 64; shift += BITS) {
            if (shift > 0 && (maxU >>> shift) == 0L) {
                break; // no higher non-zero bytes remain
            }
            Arrays.fill(count, 0);
            for (int i = 0; i < n; i++) {
                int d = (int) ((keys[i] >>> shift) & MASK);
                count[d + 1]++;
            }
            for (int d = 0; d < RADIX; d++) {
                count[d + 1] += count[d];
            }
            for (int i = 0; i < n; i++) {
                int d = (int) ((keys[i] >>> shift) & MASK);
                int pos = count[d]++;
                tmpKeys[pos] = keys[i];
                tmpItems[pos] = items[i];
            }
            long[] sk = keys;
            keys = tmpKeys;
            tmpKeys = sk;
            Object[] si = items;
            items = tmpItems;
            tmpItems = si;
        }

        for (int i = 0; i < n; i++) {
            b.set(i, (K) items[i]);
            b.recordMove();
        }
    }

    @Override
    public StrategyCapabilities capabilities() {
        return StrategyCapabilities.builder()
                .stable(true).inPlace(false).comparisonBased(false).requiresIntegerKeys(true).build();
    }

    @Override
    public StrategyId id() {
        return ID;
    }
}
