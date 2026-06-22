package io.github.richeyworks.sbskernels.rust;

import java.util.Arrays;
import java.util.Random;

/**
 * Phase 2 prototype harness (ADR {@code docs/adr-phase2-offheap-sortbuffer.md}): a quick, self-contained
 * timing of the off-heap native long radix ({@link OffHeapLongRadix}) against an inline Java LSD radix
 * baseline, on random {@code long[]} across sizes. It first verifies the two agree, then reports ms/op.
 *
 * <p>Deliberately <em>not</em> JMH and not dependent on the root module — it runs standalone in this
 * kernel module so the off-heap path can be measured without cross-module benchmark plumbing. The crude
 * warmup is enough for a directional read on whether removing FFM marshaling lets native radix beat Java;
 * a JMH version (forks, steady-state) is the rigorous follow-up before any selector-integration decision.</p>
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
        System.out.printf("off-heap long radix available: %s%n", OffHeapLongRadix.isAvailable());
        if (!OffHeapLongRadix.isAvailable()) {
            System.out.println("native flat-long kernel not loadable (need JDK 22 + cargo build --release + "
                    + "--enable-native-access). Reporting Java baseline only.");
        }

        int[] sizes = {1_000, 10_000, 100_000, 1_000_000};
        int warmup = 5;
        int measure = 12;
        Random rng = new Random(42);

        // Correctness gate: off-heap result must equal a trusted sort before any timing is meaningful.
        verifyCorrectness(rng);

        System.out.printf("%n%-10s %16s %16s %9s%n", "n", "javaRadix(ms)", "offHeapRust(ms)", "speedup");
        for (int n : sizes) {
            long[] base = randomLongs(rng, n);
            double javaMs = time(base, OffHeapRadixBenchmark::javaRadixSort, warmup, measure);
            double offMs = OffHeapLongRadix.isAvailable()
                    ? time(base, OffHeapLongRadix::sort, warmup, measure)
                    : Double.NaN;
            String speedup = Double.isNaN(offMs) ? "n/a" : String.format("%.2fx", javaMs / offMs);
            System.out.printf("%-10d %16.3f %16.3f %9s%n", n, javaMs, offMs, speedup);
        }
    }

    private static void verifyCorrectness(Random rng) {
        if (!OffHeapLongRadix.isAvailable()) {
            return;
        }
        for (int n : new int[]{0, 1, 2, 37, 1024, 50_000}) {
            long[] a = randomLongs(rng, n);
            long[] want = a.clone();
            Arrays.sort(want);
            long[] got = a.clone();
            OffHeapLongRadix.sort(got);
            if (!Arrays.equals(got, want)) {
                throw new AssertionError("off-heap radix disagrees with Arrays.sort at n=" + n);
            }
        }
        System.out.println("correctness: off-heap radix matches Arrays.sort across sizes — OK");
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
