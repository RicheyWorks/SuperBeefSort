package io.github.richeyworks.sbskernels.rust;

/**
 * Phase 2 prototype: sort a {@code long[]} ascending via the native flat-long radix kernel with
 * <em>no per-element FFM marshaling</em> — sign-flip on-heap, one bulk copy into native memory, sort
 * the segment in place, one bulk copy back, un-flip. See {@code docs/adr-phase2-offheap-sortbuffer.md}.
 *
 * <p>{@link #sort} uses the single-threaded kernel ({@code sbs_radix_sort_longs}); {@link #sortParallel}
 * uses the rayon-parallel kernel ({@code sbs_radix_sort_longs_par}, Phase 2 branch B). Each has its own
 * availability flag — when {@code false} (older cdylib, JDK &lt; 22, or native access withheld), callers
 * fall back to the pure-Java {@code radix.lsd}. This is a standalone {@code long[]} entry point, not a
 * {@code SortStrategy}: engine integration is a separate, typed fast path (see the ADR).</p>
 */
public final class OffHeapLongRadix {

    private OffHeapLongRadix() {
    }

    /** True when the single-threaded native flat-long kernel is loadable. */
    public static boolean isAvailable() {
        return RustRadixBridge.isLongsAvailable();
    }

    /** True when the rayon-parallel native flat-long kernel is loadable. */
    public static boolean isParallelAvailable() {
        return RustRadixBridge.isLongsParallelAvailable();
    }

    /** Sort {@code a} ascending in place via the single-threaded kernel. Requires {@link #isAvailable()}. */
    public static void sort(long[] a) {
        sortImpl(a, false);
    }

    /** Sort {@code a} ascending in place via the rayon-parallel kernel. Requires {@link #isParallelAvailable()}. */
    public static void sortParallel(long[] a) {
        sortImpl(a, true);
    }

    private static void sortImpl(long[] a, boolean parallel) {
        int n = a.length;
        if (n < 2) {
            return;
        }
        boolean ok = parallel ? isParallelAvailable() : isAvailable();
        if (!ok) {
            throw new IllegalStateException("native "
                    + (parallel ? "sbs_radix_sort_longs_par" : "sbs_radix_sort_longs")
                    + " unavailable; use pure-Java radix.lsd");
        }

        signFlip(a); // signed long -> unsigned u64 ordering
        try (OffHeapLongBuffer buf = OffHeapLongBuffer.of(a)) {
            if (parallel) {
                RustRadixBridge.sortLongsParallel(buf.segment(), n);
            } else {
                RustRadixBridge.sortLongs(buf.segment(), n);
            }
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
