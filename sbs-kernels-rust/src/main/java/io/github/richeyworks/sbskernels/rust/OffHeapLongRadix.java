package io.github.richeyworks.sbskernels.rust;

/**
 * Phase 2 prototype: sort a {@code long[]} ascending via the native flat-long kernel
 * ({@code sbs_radix_sort_longs}) with <em>no per-element FFM marshaling</em> — the off-heap
 * counterpart to {@code RustRadixSortStrategy}'s (key, index) pair path.
 *
 * <p>Pipeline: sign-flip on-heap (signed long → unsigned u64 order) → one bulk
 * {@link OffHeapLongBuffer#of bulk copy} into native memory → kernel sorts the segment in place →
 * one bulk copy back → un-flip. There is no index payload (the values are the keys) and no throwaway
 * {@code Arena} pack/extract, so the marshaling that capped the Phase 2 native path is gone. See
 * {@code docs/adr-phase2-offheap-sortbuffer.md}.</p>
 *
 * <p>{@link #isAvailable()} reflects whether the loaded cdylib exports the flat-long entry; when it is
 * {@code false} (older kernel, JDK &lt; 22, or native access withheld) callers should fall back to the
 * pure-Java {@code radix.lsd}. This prototype is intentionally a standalone {@code long[]} entry point,
 * not a {@code SortStrategy} — engine integration is a separate, typed fast path (see the ADR).</p>
 */
public final class OffHeapLongRadix {

    private OffHeapLongRadix() {
    }

    /** True when the native flat-long kernel is loadable on this JVM/platform. */
    public static boolean isAvailable() {
        return RustRadixBridge.isLongsAvailable();
    }

    /**
     * Sort {@code a} ascending in place. Requires {@link #isAvailable()}. On a native failure the array
     * is restored to its original contents before the exception propagates.
     */
    public static void sort(long[] a) {
        int n = a.length;
        if (n < 2) {
            return;
        }
        if (!isAvailable()) {
            throw new IllegalStateException("native sbs_radix_sort_longs unavailable; use pure-Java radix.lsd");
        }

        signFlip(a); // signed long -> unsigned u64 ordering
        try (OffHeapLongBuffer buf = OffHeapLongBuffer.of(a)) {
            RustRadixBridge.sortLongs(buf.segment(), n);
            buf.copyTo(a); // a now holds the sign-flipped values in sorted order
        } catch (Throwable t) {
            throw new RuntimeException("off-heap long radix failed", t);
        } finally {
            // Un-flip whether we succeeded (restores true order) or threw before writeback (restores input).
            signFlip(a);
        }
    }

    private static void signFlip(long[] a) {
        for (int i = 0; i < a.length; i++) {
            a[i] ^= Long.MIN_VALUE;
        }
    }
}
