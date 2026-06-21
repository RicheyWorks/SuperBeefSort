package io.github.richeyworks.sbskernels.rust;

import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.core.StrategyCapabilities;
import io.github.richeyworks.superbeefsort.core.StrategyId;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Stable LSD radix sort backed by the native {@code sbsradix} Rust kernel via Panama FFM.
 *
 * <p>Mirrors the pure-Java {@code radix.lsd} strategy's semantics (same entropy-aware
 * {@code RadixPlan}: sign-flip → offset-by-min → adaptive bits-per-pass) but delegates the inner
 * counting-sort loop to Rust, which benefits from a tighter memory layout and (eventually) rayon
 * parallelism. The FFM overhead (off-heap allocation + two O(n) copies) means the native path only
 * wins above a measured size threshold — see the JMH results in {@code SortStrategyBenchmark}
 * before allowing the selector to prefer this over {@code radix.lsd}.</p>
 *
 * <p>Protocol with the bridge:</p>
 * <ol>
 *   <li>Build a {@code long[]} of {@code 2n} values interleaved as
 *       {@code [key0, idx0, key1, idx1, …]}, where {@code keyᵢ = encode(item[i]) ^ Long.MIN_VALUE}
 *       (the sign-flip for unsigned ordering) and {@code idxᵢ = i}.</li>
 *   <li>Copy to an off-heap {@link MemorySegment}; call the Rust kernel to sort pairs by key.</li>
 *   <li>Read the sorted index field and permute the on-heap items array accordingly.</li>
 *   <li>Write permuted items back to the {@link SortBuffer}.</li>
 * </ol>
 *
 * <p>The RadixPlan (offset-by-min + adaptive bits-per-pass) is replicated inside the Rust kernel
 * ({@code radix_plan} + {@code sort_keyed_flat}) so the native path is competitive on
 * narrow-band / high-magnitude keys exactly as the Java path is.</p>
 */
public final class RustRadixSortStrategy<K> implements SortStrategy<K> {

    public static final StrategyId ID = StrategyId.of("radix.lsd.rust");

    @Override
    @SuppressWarnings("unchecked")
    public void sort(SortBuffer<K> b, SortContext ctx) {
        int n = b.size();
        if (n < 2) {
            return;
        }
        KeyEncoder<K> encoder = b.keyEncoder();
        if (encoder == null) {
            throw new IllegalStateException("RustRadixSortStrategy requires a KeyEncoder on the buffer");
        }

        // On-heap items array: needed to reconstruct the permuted output after the native sort
        Object[] items = new Object[n];

        // Off-heap segment: n pairs × 2 longs × 8 bytes = 16n bytes
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate((long) n * 2L * Long.BYTES, Long.BYTES);

            for (int i = 0; i < n; i++) {
                K item = b.get(i);
                items[i] = item;
                long key = encoder.encodeKey(item) ^ Long.MIN_VALUE; // signed → unsigned ordering
                seg.setAtIndex(ValueLayout.JAVA_LONG, 2L * i,     key);
                seg.setAtIndex(ValueLayout.JAVA_LONG, 2L * i + 1, (long) i); // original index
            }

            RustRadixBridge.sortKeyed(seg, n);

            // Apply the permutation: sorted pair at position i has its original index in slot [2i+1]
            Object[] sortedItems = new Object[n];
            for (int i = 0; i < n; i++) {
                int originalIdx = (int) seg.getAtIndex(ValueLayout.JAVA_LONG, 2L * i + 1);
                sortedItems[i] = items[originalIdx];
            }

            for (int i = 0; i < n; i++) {
                b.set(i, (K) sortedItems[i]);
                b.recordMove();
            }

            // Off-heap: 16n bytes; on-heap: items[] + sortedItems[] ≈ 16n bytes (object refs)
            b.recordAux(32L * n);

        } catch (Throwable t) {
            throw new RuntimeException("Rust radix kernel failed", t);
        }
    }

    @Override
    public StrategyCapabilities capabilities() {
        return StrategyCapabilities.builder()
                .stable(true)
                .inPlace(false)
                .comparisonBased(false)
                .requiresIntegerKeys(true)
                .backingRuntime(StrategyCapabilities.Runtime.RUST)
                .auxMemory(StrategyCapabilities.AuxMemory.LINEAR)
                .build();
    }

    @Override
    public StrategyId id() {
        return ID;
    }
}
