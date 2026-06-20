package io.github.richeyworks.superbeefsort.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The mutable region a {@link SortStrategy} sorts in place. Backed by an {@code Object[]} so
 * strategies never wrestle with generic array creation, and every {@link #compare} / {@link #swap}
 * is metered — which is what powers the engine's metrics (and, later, the step-by-step visualizer)
 * with zero bookkeeping inside each algorithm.
 *
 * <p>The buffer optionally carries a {@link KeyEncoder}; when present, the profiler and the
 * non-comparison strategies (counting, radix) read it from here, so the {@link SortStrategy}
 * signature stays the same whether a run is comparison-based or not.</p>
 *
 * <p>Phase 0/1 is on-heap only. This abstraction is deliberately the same one a Phase 2 off-heap
 * {@code MemorySegment} buffer (driven by a Rust kernel) will implement.</p>
 */
public final class SortBuffer<K> {

    private final Object[] a;
    private final Comparator<? super K> comparator;
    private final KeyEncoder<K> keyEncoder;          // may be null
    private final ByteSequenceEncoder<K> byteSequenceEncoder; // may be null
    private long comparisons;
    private long moves;
    private long peakAuxBytes;

    private SortBuffer(Object[] a, Comparator<? super K> comparator,
                       KeyEncoder<K> keyEncoder, ByteSequenceEncoder<K> byteSequenceEncoder) {
        this.a = a;
        this.comparator = comparator;
        this.keyEncoder = keyEncoder;
        this.byteSequenceEncoder = byteSequenceEncoder;
    }

    public static <K> SortBuffer<K> of(List<K> data, Comparator<? super K> comparator) {
        return new SortBuffer<>(data.toArray(), comparator, null, null);
    }

    public static <K> SortBuffer<K> of(List<K> data, Comparator<? super K> comparator, KeyEncoder<K> keyEncoder) {
        return new SortBuffer<>(data.toArray(), comparator, keyEncoder, null);
    }

    public static <K> SortBuffer<K> of(List<K> data, Comparator<? super K> comparator,
                                        KeyEncoder<K> keyEncoder, ByteSequenceEncoder<K> byteSequenceEncoder) {
        return new SortBuffer<>(data.toArray(), comparator, keyEncoder, byteSequenceEncoder);
    }

    public static <K> SortBuffer<K> of(K[] data, Comparator<? super K> comparator) {
        return of(data, comparator, null);
    }

    public static <K> SortBuffer<K> of(K[] data, Comparator<? super K> comparator, KeyEncoder<K> keyEncoder) {
        Object[] copy = new Object[data.length];
        System.arraycopy(data, 0, copy, 0, data.length);
        return new SortBuffer<>(copy, comparator, keyEncoder, null);
    }

    public int size() {
        return a.length;
    }

    @SuppressWarnings("unchecked")
    public K get(int i) {
        return (K) a[i];
    }

    public void set(int i, K value) {
        a[i] = value;
    }

    /** Compare the elements at indices {@code i} and {@code j}. */
    @SuppressWarnings("unchecked")
    public int compare(int i, int j) {
        comparisons++;
        return comparator.compare((K) a[i], (K) a[j]);
    }

    /** Compare the element at index {@code i} against an external {@code key}. */
    @SuppressWarnings("unchecked")
    public int compareToKey(int i, K key) {
        comparisons++;
        return comparator.compare((K) a[i], key);
    }

    /** Compare two free-standing values (used by merge sort across its two halves). */
    public int compareValues(K x, K y) {
        comparisons++;
        return comparator.compare(x, y);
    }

    public void swap(int i, int j) {
        Object t = a[i];
        a[i] = a[j];
        a[j] = t;
        moves += 2;
    }

    /** Record a single element move (e.g. a shift in insertion or merge sort). */
    public void recordMove() {
        moves++;
    }

    /**
     * Record a strategy's auxiliary (scratch) allocation in bytes; the buffer keeps the peak seen across a
     * run. In-place strategies never call this, so their {@link #peakAuxBytes()} stays 0 — which is what
     * lets a memory-aware observer distinguish, say, a 4-array LSD radix from a single-buffer merge even
     * though both are coarsely {@code LINEAR}. A measured signal, finer than the static capability estimate.
     */
    public void recordAux(long bytes) {
        if (bytes > peakAuxBytes) {
            peakAuxBytes = bytes;
        }
    }

    public Comparator<? super K> comparator() {
        return comparator;
    }

    public KeyEncoder<K> keyEncoder() {
        return keyEncoder;
    }

    public boolean hasKeyEncoder() {
        return keyEncoder != null;
    }

    public ByteSequenceEncoder<K> byteSequenceEncoder() {
        return byteSequenceEncoder;
    }

    public boolean hasByteSequenceEncoder() {
        return byteSequenceEncoder != null;
    }

    /** Encode the element at index {@code i}. Caller must ensure {@link #hasKeyEncoder()}. */
    public long encodeAt(int i) {
        return keyEncoder.encodeKey(get(i));
    }

    public long comparisons() {
        return comparisons;
    }

    public long moves() {
        return moves;
    }

    /** Peak auxiliary (scratch) bytes the strategy reported via {@link #recordAux}; 0 for in-place sorts. */
    public long peakAuxBytes() {
        return peakAuxBytes;
    }

    @SuppressWarnings("unchecked")
    public List<K> toList() {
        List<K> out = new ArrayList<>(a.length);
        for (Object o : a) {
            out.add((K) o);
        }
        return out;
    }
}
