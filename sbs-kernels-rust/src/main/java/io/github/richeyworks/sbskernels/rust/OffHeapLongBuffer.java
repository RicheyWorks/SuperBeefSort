package io.github.richeyworks.sbskernels.rust;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Phase 2 prototype (ADR {@code docs/adr-phase2-offheap-sortbuffer.md}): a {@link MemorySegment}-backed
 * buffer of {@code long} keys — the off-heap analogue of {@code core/SortBuffer}'s {@code get/set/size}
 * primitives, for the radix fast path.
 *
 * <p>The point is to give the native kernel something it can sort <em>in place</em> with no per-element
 * FFM marshaling. {@link #of(long[])} bulk-copies a {@code long[]} into native memory in one
 * {@link MemorySegment#copy} (memcpy-class), the kernel sorts the {@link #segment()} in place, and
 * {@link #copyTo(long[])} bulk-copies the result back — replacing the {@code RustRadixSortStrategy}
 * path's 2n {@code setAtIndex} pack + n {@code getAtIndex} extract (per-element {@code VarHandle}
 * accesses) that erased the native margin in the Phase 2 JMH results.</p>
 *
 * <p>Backed by a confined {@link Arena}; {@link #close()} frees the native memory, so callers should use
 * try-with-resources. Holds only primitive longs (off-heap can't hold arbitrary {@code K} objects) — see
 * the ADR for why this targets the primitive-long fast path rather than general {@code SortBuffer<K>}.</p>
 */
public final class OffHeapLongBuffer implements AutoCloseable {

    private static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG;

    private final Arena arena;
    private final MemorySegment seg;
    private final int size;

    private OffHeapLongBuffer(Arena arena, MemorySegment seg, int size) {
        this.arena = arena;
        this.seg = seg;
        this.size = size;
    }

    /** Allocate an uninitialised off-heap buffer of {@code size} longs (8-byte aligned, confined). */
    public static OffHeapLongBuffer allocate(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("size must be >= 0: " + size);
        }
        Arena a = Arena.ofConfined();
        MemorySegment s = a.allocate((long) size * Long.BYTES, Long.BYTES);
        return new OffHeapLongBuffer(a, s, size);
    }

    /** Allocate and bulk-copy {@code data} into native memory in one transfer (no per-element writes). */
    public static OffHeapLongBuffer of(long[] data) {
        OffHeapLongBuffer buf = allocate(data.length);
        if (data.length > 0) {
            MemorySegment.copy(data, 0, buf.seg, L, 0L, data.length);
        }
        return buf;
    }

    public int size() {
        return size;
    }

    public long get(int i) {
        return seg.getAtIndex(L, i);
    }

    public void set(int i, long v) {
        seg.setAtIndex(L, i, v);
    }

    /** The native segment ({@code size} consecutive u64/long values) for an in-place kernel call. */
    public MemorySegment segment() {
        return seg;
    }

    /** Bulk-copy the buffer's contents back into {@code out} (length must be {@code >= size}). */
    public void copyTo(long[] out) {
        if (out.length < size) {
            throw new IllegalArgumentException("out too small: " + out.length + " < " + size);
        }
        if (size > 0) {
            MemorySegment.copy(seg, L, 0L, out, 0, size);
        }
    }

    public long[] toArray() {
        long[] out = new long[size];
        copyTo(out);
        return out;
    }

    @Override
    public void close() {
        arena.close();
    }
}
