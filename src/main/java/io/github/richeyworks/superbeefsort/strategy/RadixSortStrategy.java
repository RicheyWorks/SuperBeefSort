package io.github.richeyworks.superbeefsort.strategy;

import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.core.StrategyCapabilities;
import io.github.richeyworks.superbeefsort.core.StrategyId;

import java.util.Arrays;

/**
 * Stable least-significant-digit radix sort over 64-bit integer keys, with an <em>entropy-aware</em> pass
 * plan. Keys are sign-flipped for correct unsigned digit ordering, then offset by the unsigned minimum so
 * the sort runs over only the significant bits of the key <em>range</em>; {@link RadixPlan} then picks the
 * bits-per-pass and pass count that minimize total work for that range width and {@code n}.
 *
 * <p>The offset-by-min step is the key win over a fixed schedule: the sign flip leaves the high bit set for
 * non-negative keys, so a naive plan runs a full eight passes even for a narrow band of large values.
 * Subtracting the minimum collapses such a band to a few low bits — ids in {@code [1_000_000, 1_001_000]}
 * sort in a single ~10-bit pass instead of eight. Requires a {@link KeyEncoder}; stable; out of place.</p>
 */
public final class RadixSortStrategy<K> implements SortStrategy<K> {

    public static final StrategyId ID = StrategyId.of("radix.lsd");

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
        long minU = 0xFFFFFFFFFFFFFFFFL; // unsigned max, reduced downward
        long maxU = 0L;                  // unsigned min, reduced upward
        for (int i = 0; i < n; i++) {
            long u = encoder.encodeKey(b.get(i)) ^ Long.MIN_VALUE; // signed -> unsigned ordering
            keys[i] = u;
            items[i] = b.get(i);
            // Unsigned compares: after the sign flip a signed `<`/`>` would mis-rank non-negative keys
            // (their top bit is set), so min/max must use Long.compareUnsigned.
            if (Long.compareUnsigned(u, minU) < 0) {
                minU = u;
            }
            if (Long.compareUnsigned(u, maxU) > 0) {
                maxU = u;
            }
        }

        // Sort over the range, not the absolute magnitude: only the bits in (maxU - minU) can differ.
        long range = maxU - minU; // unsigned subtraction; bit pattern is correct even when it exceeds 2^63
        int significantBits = 64 - Long.numberOfLeadingZeros(range);
        RadixPlan plan = RadixPlan.forWidth(significantBits, n);

        for (int i = 0; i < n; i++) {
            keys[i] -= minU; // offset into [0, range]
        }

        int bits = plan.bitsPerPass();
        int radix = plan.radix();
        int mask = plan.mask();
        long[] tmpKeys = new long[n];
        Object[] tmpItems = new Object[n];
        int[] count = new int[radix + 1];
        b.recordAux(32L * n + 4L * (radix + 1)); // long+Object keys/items and their tmp copies, + count[radix]

        for (int p = 0; p < plan.passes(); p++) {
            int shift = p * bits;
            Arrays.fill(count, 0);
            for (int i = 0; i < n; i++) {
                int d = (int) ((keys[i] >>> shift) & mask);
                count[d + 1]++;
            }
            for (int d = 0; d < radix; d++) {
                count[d + 1] += count[d];
            }
            for (int i = 0; i < n; i++) {
                int d = (int) ((keys[i] >>> shift) & mask);
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
                .stable(true).inPlace(false).comparisonBased(false).requiresIntegerKeys(true)
                .auxMemory(StrategyCapabilities.AuxMemory.LINEAR).build();   // O(n) bucket/output arrays
    }

    @Override
    public StrategyId id() {
        return ID;
    }
}
