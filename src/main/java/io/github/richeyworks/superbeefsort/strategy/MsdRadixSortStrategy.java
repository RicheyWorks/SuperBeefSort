package io.github.richeyworks.superbeefsort.strategy;

import io.github.richeyworks.superbeefsort.core.ByteSequenceEncoder;
import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.core.StrategyCapabilities;
import io.github.richeyworks.superbeefsort.core.StrategyId;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;

/**
 * Stable most-significant-digit (MSD) radix sort for variable-length keys exposed as byte sequences via
 * a {@link ByteSequenceEncoder} — the string / byte-array path that the {@code long}-keyed counting and
 * LSD-radix sorts cannot serve. At each depth it distributes the current range into {@value #RADIX}+1
 * buckets (bucket {@code 0} is "this key ended here", so prefixes sort before their extensions; buckets
 * {@code 1..256} are the byte value) and then recurses into each byte bucket at the next depth. Ranges
 * at or below a small {@code cutoff} fall back to a stable insertion sort over the real comparator, which
 * also makes the result correct for <em>any</em> faithful encoder.
 *
 * <p>Recursion is driven by an explicit stack (not the call stack), so arbitrarily long keys can't blow
 * the JVM stack. Distribution is stable and the base case is stable, so the whole sort is stable. It is
 * not auto-selected by the engine (the profiler/selector are built around the single-{@code long}
 * {@link io.github.richeyworks.superbeefsort.core.KeyEncoder}); construct it directly with an encoder, or
 * use {@code BeefSort.sortByteKeys(...)}.</p>
 */
public final class MsdRadixSortStrategy<K> implements SortStrategy<K> {

    public static final StrategyId ID = StrategyId.of("radix.msd");
    private static final int RADIX = 256;
    private static final int BUCKETS = RADIX + 1; // bucket 0 = end-of-sequence; 1..256 = byte value + 1
    private static final int DEFAULT_CUTOFF = 24;

    private final ByteSequenceEncoder<K> encoder; // may be null when registered without one (reads from buffer)
    private final int cutoff;

    /** Registry constructor: encoder is resolved from the {@link SortBuffer} at sort time. */
    public MsdRadixSortStrategy() {
        this(null, DEFAULT_CUTOFF);
    }

    public MsdRadixSortStrategy(ByteSequenceEncoder<K> encoder) {
        this(encoder, DEFAULT_CUTOFF);
    }

    public MsdRadixSortStrategy(ByteSequenceEncoder<K> encoder, int cutoff) {
        this.encoder = encoder; // null allowed when used as registry singleton
        this.cutoff = Math.max(2, cutoff);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void sort(SortBuffer<K> b, SortContext context) {
        ByteSequenceEncoder<K> enc = this.encoder != null ? this.encoder : b.byteSequenceEncoder();
        if (enc == null) {
            throw new IllegalStateException(
                    "MsdRadixSortStrategy requires a ByteSequenceEncoder: supply one in the constructor "
                    + "or via BeefSort.byteSequenceEncoder(...)");
        }
        int n = b.size();
        if (n < 2) {
            return;
        }
        Object[] items = new Object[n];
        for (int i = 0; i < n; i++) {
            items[i] = b.get(i);
        }
        Object[] aux = new Object[n];
        int[] count = new int[BUCKETS + 1];
        b.recordAux(16L * n + 4L * (BUCKETS + 1)); // Object items[n] + aux[n] + int count[BUCKETS]

        ArrayDeque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{0, n, 0});
        while (!stack.isEmpty()) {
            int[] frame = stack.pop();
            int lo = frame[0];
            int hi = frame[1];
            int depth = frame[2];
            int len = hi - lo;
            if (len <= 1) {
                continue;
            }
            if (len <= cutoff) {
                insertion(items, lo, hi, b);
                continue;
            }

            Arrays.fill(count, 0, BUCKETS + 1, 0);
            for (int i = lo; i < hi; i++) {
                count[bucket((K) items[i], depth, enc) + 1]++;
            }
            for (int c = 0; c < BUCKETS; c++) {
                count[c + 1] += count[c];
            }
            // count[bkt] is now the start offset (within [lo,hi)) of bucket bkt; copy it so the stable
            // distribution below can advance offsets without losing the bucket boundaries for recursion.
            int[] start = Arrays.copyOf(count, BUCKETS);
            for (int i = lo; i < hi; i++) {
                int bkt = bucket((K) items[i], depth, enc);
                aux[lo + start[bkt]++] = items[i];
            }
            System.arraycopy(aux, lo, items, lo, len);

            // Recurse into each non-trivial byte bucket (1..256). Bucket 0 holds keys that ended at this
            // depth — they share the full prefix and are therefore equal, so they need no further sorting.
            for (int bkt = 1; bkt < BUCKETS; bkt++) {
                int sLo = lo + count[bkt];
                int sHi = lo + count[bkt + 1];
                if (sHi - sLo > 1) {
                    stack.push(new int[]{sLo, sHi, depth + 1});
                }
            }
        }

        for (int i = 0; i < n; i++) {
            b.set(i, (K) items[i]);
            b.recordMove();
        }
    }

    private int bucket(K key, int depth, ByteSequenceEncoder<K> enc) {
        return depth < enc.length(key) ? enc.byteAt(key, depth) + 1 : 0;
    }

    /** Stable insertion sort over the real comparator on {@code items[lo,hi)} — the small-range base case. */
    @SuppressWarnings("unchecked")
    private void insertion(Object[] items, int lo, int hi, SortBuffer<K> b) {
        for (int i = lo + 1; i < hi; i++) {
            Object key = items[i];
            int j = i - 1;
            while (j >= lo && b.compareValues((K) items[j], (K) key) > 0) {
                items[j + 1] = items[j];
                j--;
            }
            items[j + 1] = key;
        }
    }

    @Override
    public StrategyCapabilities capabilities() {
        return StrategyCapabilities.builder()
                .stable(true).inPlace(false).comparisonBased(false)
                .requiresByteSequenceEncoder(true)
                .auxMemory(StrategyCapabilities.AuxMemory.LINEAR).build();   // O(n) bucket/output arrays
    }

    @Override
    public StrategyId id() {
        return ID;
    }
}
