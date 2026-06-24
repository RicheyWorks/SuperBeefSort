package io.github.richeyworks.superbeefsort.strategy;

import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.core.StrategyCapabilities;
import io.github.richeyworks.superbeefsort.core.StrategyId;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.function.IntConsumer;

/**
 * Multi-threaded, stable least-significant-digit radix sort over 64-bit integer keys — the parallel sibling
 * of {@link RadixSortStrategy} (ID {@code radix.lsd.parallel}). It reuses the same entropy-aware
 * {@link RadixPlan} key encoding (sign-flip for unsigned digit ordering, offset-by-min so only the varying
 * bits of the key <em>range</em> are swept), but parallelizes each counting pass with the classic
 * <b>chunked-histogram → disjoint-offset prefix → parallel scatter</b> schedule:
 *
 * <ol>
 *   <li><b>histogram (parallel):</b> the key array is split into {@code p} contiguous chunks; each thread
 *       counts its chunk's digit frequencies into its own private {@code count[chunk][digit]} row, so there
 *       is no shared-counter contention;</li>
 *   <li><b>offsets (sequential):</b> a single prefix pass turns those rows into a starting write offset per
 *       {@code (chunk, digit)} pair, ordered <em>digit-major then chunk-major</em>. That ordering is exactly
 *       what makes the parallel pass stable: for a given digit, chunk&nbsp;0's elements are placed before
 *       chunk&nbsp;1's, and within a chunk the in-order scan preserves original order;</li>
 *   <li><b>scatter (parallel):</b> each thread walks its chunk in order and writes each element to its
 *       {@code (chunk, digit)} cursor. Every {@code (chunk, digit)} pair owns a disjoint, contiguous output
 *       range, so the threads never write the same slot — no locks, no atomics in the inner loop.</li>
 * </ol>
 *
 * <p>The output is byte-for-byte identical to the sequential {@code radix.lsd} for any chunk count, so the
 * result does not depend on the host's core count (only the intermediate scheduling does); it is therefore
 * deterministic and stable regardless of {@code p}. All {@link SortBuffer} interaction (encode, the final
 * write-back) is single-threaded, so the buffer's metering stays correct and no concurrent access reaches it;
 * the parallel work happens entirely on private primitive arrays.</p>
 *
 * <p><b>Why the radix is capped in parallel.</b> The per-pass overhead beyond the data scan is
 * {@code radix × p} (zeroing and prefixing the histogram matrix). The entropy plan can pick a wide base
 * (up to {@value RadixPlan#MAX_BITS} bits ⇒ a 65&nbsp;536-entry radix) which, multiplied by the chunk count,
 * can dominate and erase the parallel speedup — exactly the regression recorded in
 * {@code docs/adr-phase2-offheap-sortbuffer.md} (the native rayon prototype was "catastrophic" until its
 * radix was capped at 8 bits). So when running multi-threaded this strategy caps the base at
 * {@value #PARALLEL_MAX_BITS} bits ({@value #PARALLEL_RADIX}-entry histogram); the trade of a few more
 * passes for a far smaller per-pass matrix is the one the ADR found wins at scale. The single-threaded path
 * keeps the uncapped entropy plan, so below the threshold it is identical to {@code radix.lsd}.</p>
 *
 * <p>Requires a {@link KeyEncoder}; stable; out of place; {@code O(n)} auxiliary memory. This is the
 * pure-Java realization of the ADR's closing recommendation — capture the only lever that helped the native
 * kernel (multicore) in Java, with none of the FFM marshaling, {@code unsafe}, JDK-22, or {@code long[]}-only
 * payload constraints of the Rust path.</p>
 */
public final class ParallelRadixSortStrategy<K> implements SortStrategy<K> {

    public static final StrategyId ID = StrategyId.of("radix.lsd.parallel");

    /**
     * Below this {@code n}, thread-coordination overhead outweighs the parallel gain, so the strategy runs
     * single-threaded — byte-for-byte identical to {@code radix.lsd}. Public so the selectors can reuse it as
     * the crossover for routing wide-range integer inputs to {@code radix.lsd.parallel}: routing below this
     * point is pointless (the strategy would not actually fan out), and at/above it the threads engage.
     * Confirmed by {@code bench/ParallelRadixBenchmark} (de-confounded 3-fork run, 2026-06-23):
     * {@code radix.lsd.parallel} is significantly faster than {@code radix.lsd} at every {@code n} &gt;= this
     * threshold (1.21&times; at 100k rising to 2.61&times; at 5M).
     */
    public static final int PARALLEL_THRESHOLD = 1 << 16; // 65_536

    /** Smallest chunk worth handing to a thread; bounds the chunk count so chunks stay cache-friendly. */
    static final int MIN_CHUNK = 1 << 14; // 16_384

    /** Cap on bits-per-pass when parallel, so the {@code radix × chunks} histogram matrix stays small (ADR). */
    static final int PARALLEL_MAX_BITS = 8;

    /** Histogram width at the parallel cap. */
    static final int PARALLEL_RADIX = 1 << PARALLEL_MAX_BITS; // 256

    /** Fixed chunk count, or {@code 0} to derive it from the common pool / CPU count at sort time. */
    private final int parallelism;

    public ParallelRadixSortStrategy() {
        this(0);
    }

    /**
     * @param parallelism number of chunks/threads to use; {@code 0} derives it from the available CPUs.
     *                    A positive value forces the parallel path even below {@link #PARALLEL_THRESHOLD}
     *                    (useful for tests); {@code 1} forces the sequential path.
     */
    public ParallelRadixSortStrategy(int parallelism) {
        if (parallelism < 0) {
            throw new IllegalArgumentException("parallelism must be >= 0");
        }
        this.parallelism = parallelism;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void sort(SortBuffer<K> b, SortContext context) {
        int n = b.size();
        if (n < 2) {
            return;
        }
        KeyEncoder<K> encoder = b.keyEncoder();
        if (encoder == null) {
            throw new IllegalStateException("ParallelRadixSortStrategy requires a KeyEncoder on the buffer");
        }

        // ---- encode + unsigned min/max scan (single-threaded: the only buffer reads) ----
        long[] keys = new long[n];
        Object[] items = new Object[n];
        long minU = 0xFFFFFFFFFFFFFFFFL; // unsigned max, reduced downward
        long maxU = 0L;                  // unsigned min, reduced upward
        for (int i = 0; i < n; i++) {
            long u = encoder.encodeKey(b.get(i)) ^ Long.MIN_VALUE; // signed -> unsigned ordering
            keys[i] = u;
            items[i] = b.get(i);
            if (Long.compareUnsigned(u, minU) < 0) {
                minU = u;
            }
            if (Long.compareUnsigned(u, maxU) > 0) {
                maxU = u;
            }
        }

        long range = maxU - minU; // unsigned subtraction; bit pattern correct even past 2^63
        int significantBits = 64 - Long.numberOfLeadingZeros(range);
        for (int i = 0; i < n; i++) {
            keys[i] -= minU; // collapse to [0, range]
        }

        int p = chunkCount(n);

        // Entropy plan for the base; cap bits-per-pass when parallel to bound the radix*p matrix work.
        RadixPlan base = RadixPlan.forWidth(significantBits, n);
        int bits = (p > 1) ? Math.min(base.bitsPerPass(), PARALLEL_MAX_BITS) : base.bitsPerPass();
        int passes = (significantBits <= 0) ? 0 : (significantBits + bits - 1) / bits;
        int radix = 1 << bits;
        int mask = radix - 1;

        long[] tmpKeys = new long[n];
        Object[] tmpItems = new Object[n];
        int[][] count = new int[p][radix]; // private histogram/cursor row per chunk
        b.recordAux(32L * n + 4L * (long) p * radix); // keys+items + tmp copies, + per-chunk count rows

        ForkJoinPool pool = ForkJoinPool.commonPool();
        for (int pass = 0; pass < passes; pass++) {
            int shift = pass * bits;
            long[] srcK = keys;
            Object[] srcI = items;
            long[] dstK = tmpKeys;
            Object[] dstI = tmpItems;

            // 1. parallel histogram: each chunk fills its own private count row (zeroed first).
            parallelFor(pool, p, c -> {
                int[] cc = count[c];
                java.util.Arrays.fill(cc, 0);
                int lo = chunkLo(c, p, n);
                int hi = chunkLo(c + 1, p, n);
                for (int i = lo; i < hi; i++) {
                    cc[(int) ((srcK[i] >>> shift) & mask)]++;
                }
            });

            // 2. sequential disjoint-offset prefix: digit-major then chunk-major => stable placement.
            int sum = 0;
            for (int d = 0; d < radix; d++) {
                for (int c = 0; c < p; c++) {
                    int cnt = count[c][d];
                    count[c][d] = sum; // (chunk, digit) start offset
                    sum += cnt;
                }
            }

            // 3. parallel scatter: each chunk owns disjoint output ranges, so writes never collide.
            parallelFor(pool, p, c -> {
                int[] cc = count[c];
                int lo = chunkLo(c, p, n);
                int hi = chunkLo(c + 1, p, n);
                for (int i = lo; i < hi; i++) {
                    int d = (int) ((srcK[i] >>> shift) & mask);
                    int pos = cc[d]++;
                    dstK[pos] = srcK[i];
                    dstI[pos] = srcI[i];
                }
            });

            keys = dstK;
            tmpKeys = srcK;
            items = dstI;
            tmpItems = srcI;
        }

        // ---- single-threaded write-back (keeps buffer metering correct) ----
        for (int i = 0; i < n; i++) {
            b.set(i, (K) items[i]);
            b.recordMove();
        }
    }

    /** Number of chunks to split into: 1 below the threshold (unless forced), else bounded by CPUs and n. */
    private int chunkCount(int n) {
        if (parallelism > 0) {
            return Math.max(1, Math.min(parallelism, n)); // forced; never more chunks than elements
        }
        if (n < PARALLEL_THRESHOLD) {
            return 1;
        }
        int procs = Math.max(1, ForkJoinPool.getCommonPoolParallelism());
        return Math.max(1, Math.min(procs, n / MIN_CHUNK));
    }

    /** Even split: chunk {@code c} of {@code p} over {@code n} starts here (handles p &gt; n: empty chunks). */
    private static int chunkLo(int c, int p, int n) {
        return (int) ((long) c * n / p);
    }

    /** Run {@code body} for chunks {@code 0..parts-1}; chunk 0 on the caller, the rest on the common pool. */
    private static void parallelFor(ForkJoinPool pool, int parts, IntConsumer body) {
        if (parts <= 1) {
            body.accept(0);
            return;
        }
        ForkJoinTask<?>[] tasks = new ForkJoinTask<?>[parts - 1];
        for (int c = 1; c < parts; c++) {
            int chunk = c;
            tasks[c - 1] = pool.submit(() -> body.accept(chunk));
        }
        body.accept(0); // use the caller thread too
        RuntimeException error = null;
        for (ForkJoinTask<?> t : tasks) {
            try {
                t.join();
            } catch (RuntimeException e) {
                error = e; // surface the first task failure after all have settled
            }
        }
        if (error != null) {
            throw error;
        }
    }

    @Override
    public StrategyCapabilities capabilities() {
        return StrategyCapabilities.builder()
                .stable(true).inPlace(false).comparisonBased(false).parallel(true).requiresIntegerKeys(true)
                .auxMemory(StrategyCapabilities.AuxMemory.LINEAR).build();   // O(n) bucket/output arrays
    }

    @Override
    public StrategyId id() {
        return ID;
    }
}
