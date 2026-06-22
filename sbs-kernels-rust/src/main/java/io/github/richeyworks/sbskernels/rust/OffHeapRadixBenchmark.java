package io.github.richeyworks.sbskernels.rust;

import java.util.Arrays;
import java.util.Random;

/**
 * Phase 2 prototype harness (ADR {@code docs/adr-phase2-offheap-sortbuffer.md}): a quick, self-contained
 * timing of the off-heap native long radix — single-threaded ({@link OffHeapLongRadix#sort}) and
 * rayon-parallel ({@link OffHeapLongRadix#sortParallel}, branch B) — against an inline Java LSD radix
 * baseline, on random {@code long[]} across sizes. It verifies all variants agree, then reports ms/op.
 *
 * <p>Deliberately not JMH and not dependent on the root module — runs standalone in this kernel module.
 * The crude warmup is enough for a directional read on whether parallelism flips the n=1M loss the
 * single-threaded off-heap path showed (0.64×); a JMH version (forks, steady state), measured against
 * the production entropy-aware {@code radix.lsd} rather than this fixed-8-pass baseline, is the rigorous
 * follow-up before any selector-integration decision.</p>
 *
 * <p>Run (JDK 22, after {@code cargo build --release} bundles the cdylib):
 * {@code ./gradlew :sbs-kernels-rust:offHeapBench}</p>
 */
public final class OffHeapRadixBenchmark {

    private OffHeapRadixBenchmark() {
    }

    @FunctionalInterface
    private interface LongSort {
        void sort(long[] a);
    }

    public static void main(String[] args) {
        boolean seq = OffHeapLongRadix.isAvailable();
        boolean par = OffHeapLongRadix.isParallelAvailable();
        System.out.printf("off-heap long radix: sequential=%s  parallel=%s%n", seq, par);
        if (!seq) {
            System.out.println("native flat-long kernel not loadable (need JDK 22 + cargo build --release + "
                    + "--enable-native-access). Reporting Java baseline only.");
        }

        int[] sizes = {1_000, 10_000, 100_000, 1_000_000, 5_000_000};
        int warmup = 5;
        int measure = 12;
        Random rng = new Random(42);

        verifyCorrectness(rng);

        System.out.printf("%n%-10s %12s %12s %12s %8s %8s%n",
                "n", "java(ms)", "seq(ms)", "par(ms)", "seqX", "parX");
        for (int n : sizes) {
            long[] base = randomLongs(rng, n);
            double javaMs = time(base, OffHeapRadixBenchmark::javaRadixSort, warmup, measure);
            double seqMs = seq ? time(base, OffHeapLongRadix::sort, warmup, measure) : Double.NaN;
            double parMs = par ? time(base, OffHeapLongRadix::sortParallel, warmup, measure) : Double.NaN;
            System.out.printf("%-10d %12.3f %12.3f %12.3f %8s %8s%n",
                    n, javaMs, seqMs, parMs,
                    fmtX(javaMs, seqMs), fmtX(javaMs, parMs));
        }
    }

    private static String fmtX(double javaMs, double otherMs) {
        return Double.isNaN(otherMs) ? "n/a" : String.format("%.2fx", javaMs / otherMs);
    }

    private static void verifyCorrectness(Random rng) {
        for (int n : new int[]{0, 1, 2, 37, 1024, 70_000, 200_000}) {
            long[] a = randomLongs(rng, n);
            long[] want = a.clone();
            Arrays.sort(want);
            if (OffHeapLongRadix.isAvailable()) {
                long[] got = a.clone();
                OffHeapLongRadix.sort(got);
                if (!Arrays.equals(got, want)) {
                    throw new AssertionError("off-heap sequential radix disagrees with Arrays.sort at n=" + n);
                }
            }
            if (OffHeapLongRadix.isParallelAvailable()) {
                long[] gotPar = a.clone();
                OffHeapLongRadix.sortParallel(gotPar);
                if (!Arrays.equals(gotPar, want)) {
                    throw new AssertionError("off-heap parallel radix disagrees with Arrays.sort at n=" + n);
                }
            }
        }
        System.out.println("correctness: off-heap radix (seq + par) matches Arrays.sort across sizes — OK");
    }

    private static long[] randomLongs(Random rng, int n) {
        long[] a = new long[n];
        for (int i = 0; i < n; i++) {
            a[i] = rng.nextLong();
        }
        return a;
    }

    private static double time(long[] base, LongSort sort, int warmup, int measure) {
        for (int w = 0; w < warmup; w++) {
            sort.sort(base.clone());
        }
        long totalNanos = 0;
        long sink = 0;
        for (int m = 0; m < measure; m++) {
            long[] a = base.clone();
            long t0 = System.nanoTime();
            sort.sort(a);
            totalNanos += System.nanoTime() - t0;
            sink += a[a.length >>> 1]; // touch the output so the sort can't be eliminated
        }
        if (sink == Long.MIN_VALUE + 1) {
            System.out.print(""); // keep `sink` live without affecting timing
        }
        return totalNanos / 1e6 / measure;
    }

    /**
     * Inline signed-long LSD radix, 8 bits/pass, sign-flipped for correct ordering. Self-contained
     * baseline (not the entropy-aware production {@code radix.lsd}); for random full-range longs the
     * fixed 8-pass schedule is representative.
     */
    static void javaRadixSort(long[] a) {
        int n = a.length;
        if (n < 2) {
            return;
        }
        long[] src = new long[n];
        for (int i = 0; i < n; i++) {
            src[i] = a[i] ^ Long.MIN_VALUE;
        }
        long[] dst = new long[n];
        int[] count = new int[257];
        for (int shift = 0; shift < 64; shift += 8) {
            Arrays.fill(count, 0);
            for (int i = 0; i < n; i++) {
                count[(int) ((src[i] >>> shift) & 0xFF) + 1]++;
            }
            for (int d = 0; d < 256; d++) {
                count[d + 1] += count[d];
            }
            for (int i = 0; i < n; i++) {
                int digit = (int) ((src[i] >>> shift) & 0xFF);
                dst[count[digit]++] = src[i];
            }
            long[] tmp = src;
            src = dst;
            dst = tmp;
        }
        for (int i = 0; i < n; i++) {
            a[i] = src[i] ^ Long.MIN_VALUE;
        }
    }
}
