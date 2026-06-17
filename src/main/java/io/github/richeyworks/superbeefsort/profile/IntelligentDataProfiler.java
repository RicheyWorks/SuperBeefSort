package io.github.richeyworks.superbeefsort.profile;

import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.core.SortBuffer;

import java.util.Comparator;

/**
 * The Phase 1 "inspection station". In addition to sortedness, it estimates distinct-key count with
 * a {@link Hll HyperLogLog}, and — when the buffer carries a {@link KeyEncoder} — computes integer
 * {@link KeyStats} and a {@link Distribution}. It first validates that the encoder is order-faithful
 * on a sample; if it is not, integer stats are withheld so the selector stays on comparison sorts
 * (never silently corrupting order).
 */
public final class IntelligentDataProfiler<K> implements DataProfiler<K> {

    private static final int HISTOGRAM_BUCKETS = 16;
    private static final long COUNTING_RANGE_CAP = 1L << 24;
    /** At or below this size an exact merge-count is cheap, so we always compute inversions exactly. */
    private static final int INVERSION_EXACT_MAX = 1 << 13; // 8192
    /** Strided sample size for the inversion estimate on larger SHALLOW inputs. */
    private static final int INVERSION_SAMPLE = 1 << 11;    // 2048

    @Override
    public DataProfile profile(SortBuffer<K> b, ProfileDepth depth) {
        int n = b.size();
        if (n < 2) {
            return new DataProfile(n, 1.0, false, depth, n, null, Distribution.UNKNOWN, n, 0L, true,
                    b.hasByteSequenceEncoder());
        }

        long inOrder = 0;
        long pairs = 0;
        boolean duplicates = false;
        int curRun = 1;
        int longestRun = 1;
        for (int i = 1; i < n; i++) {
            int c = b.compare(i - 1, i);
            if (c <= 0) {
                inOrder++;
                curRun++;
                if (curRun > longestRun) {
                    longestRun = curRun;
                }
            } else {
                curRun = 1;
            }
            if (c == 0) {
                duplicates = true;
            }
            pairs++;
        }
        double ratio = (double) inOrder / pairs;

        // Global disorder: exact for small/DEEP inputs, a strided-sample estimate otherwise. Uses the
        // raw comparator (like monotonic() below), so it never inflates the metered buffer counters.
        boolean invExact = depth == ProfileDepth.DEEP || n <= INVERSION_EXACT_MAX;
        long inversions = invExact
                ? countInversionsExact(b, n)
                : estimateInversions(b, n, INVERSION_SAMPLE);

        KeyEncoder<K> encoder = b.keyEncoder();
        if (encoder == null) {
            Hll hll = new Hll();
            for (int i = 0; i < n; i++) {
                K v = b.get(i);
                hll.add(mix(v == null ? 0L : v.hashCode()));
            }
            return new DataProfile(n, ratio, duplicates, depth, hll.estimate(), null, Distribution.UNKNOWN,
                    longestRun, inversions, invExact, b.hasByteSequenceEncoder());
        }

        long[] keys = new long[n];
        for (int i = 0; i < n; i++) {
            keys[i] = encoder.encodeKey(b.get(i));
        }

        if (!monotonic(b, keys, n)) {
            Hll hll = new Hll();
            for (long k : keys) {
                hll.add(mix(k));
            }
            return new DataProfile(n, ratio, duplicates, depth, hll.estimate(), null, Distribution.UNKNOWN,
                    longestRun, inversions, invExact, b.hasByteSequenceEncoder());
        }

        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        Hll hll = new Hll();
        for (long k : keys) {
            if (k < min) {
                min = k;
            }
            if (k > max) {
                max = k;
            }
            hll.add(mix(k));
        }
        long span = max - min;
        boolean countingFeasible = span >= 0 && span < COUNTING_RANGE_CAP;
        KeyStats keyStats = new KeyStats(min, max, countingFeasible);

        Distribution distribution;
        if (span <= 0) {
            distribution = Distribution.CLUSTERED;
        } else {
            int[] histogram = new int[HISTOGRAM_BUCKETS];
            for (long k : keys) {
                int bucket = (int) (((k - min) * HISTOGRAM_BUCKETS) / (span + 1));
                if (bucket >= HISTOGRAM_BUCKETS) {
                    bucket = HISTOGRAM_BUCKETS - 1;
                } else if (bucket < 0) {
                    bucket = 0;
                }
                histogram[bucket]++;
            }
            distribution = classify(histogram, n);
        }

        return new DataProfile(n, ratio, duplicates, depth, hll.estimate(), keyStats, distribution,
                longestRun, inversions, invExact, b.hasByteSequenceEncoder());
    }

    /** Sample adjacent pairs and confirm the encoding agrees with the comparator's strict order. */
    private boolean monotonic(SortBuffer<K> b, long[] keys, int n) {
        int checks = Math.min(n - 1, 64);
        int step = Math.max(1, (n - 1) / checks);
        Comparator<? super K> cmp = b.comparator();
        for (int i = 0; i + step < n; i += step) {
            int sign = Integer.signum(cmp.compare(b.get(i), b.get(i + step)));
            if (sign != 0 && Long.signum(Long.compare(keys[i], keys[i + step])) != sign) {
                return false;
            }
        }
        return true;
    }

    /** Exact inversion count over the whole input via an O(n log n) merge-count. */
    private long countInversionsExact(SortBuffer<K> b, int n) {
        Object[] a = new Object[n];
        for (int i = 0; i < n; i++) {
            a[i] = b.get(i);
        }
        return mergeCount(a, new Object[n], b.comparator());
    }

    /**
     * Estimate inversions from an order-preserving strided sample of {@code m} positions. Each pair
     * survives sub-sampling with probability {@code C(m,2)/C(n,2)}, so the sample's exact inversion
     * count scaled by {@code C(n,2)/C(m,2)} is an unbiased estimator of the total. Cheap (O(m log m))
     * but blind to disorder finer than the stride -- which is why small/DEEP inputs are counted exactly.
     */
    private long estimateInversions(SortBuffer<K> b, int n, int m) {
        if (m >= n) {
            return countInversionsExact(b, n);
        }
        Object[] a = new Object[m];
        for (int k = 0; k < m; k++) {
            int idx = (int) ((long) k * n / m); // strictly increasing for m <= n: preserves order
            a[k] = b.get(idx);
        }
        long sampleInv = mergeCount(a, new Object[m], b.comparator());
        double maxSamplePairs = (double) m * (m - 1) / 2.0;
        double maxInv = (double) n * (n - 1) / 2.0;
        if (maxSamplePairs <= 0.0) {
            return 0L;
        }
        return Math.round(sampleInv / maxSamplePairs * maxInv);
    }

    /**
     * Bottom-up (iterative) merge sort that counts strict inversions; iterative to avoid the deep
     * recursion a worst-case input could otherwise drive. Ties are not inversions (matches
     * {@code sortednessRatio}'s {@code c <= 0} ordering). {@code a} and {@code tmp} are ping-ponged;
     * only the count is returned, so the final sorted location does not matter.
     */
    private static long mergeCount(Object[] a, Object[] tmp, Comparator<?> cmpRaw) {
        @SuppressWarnings("unchecked")
        Comparator<Object> cmp = (Comparator<Object>) cmpRaw;
        int n = a.length;
        long inversions = 0;
        for (int width = 1; width < n; width <<= 1) {
            for (int lo = 0; lo < n; lo += (width << 1)) {
                int mid = Math.min(lo + width, n);
                int hi = Math.min(lo + (width << 1), n);
                inversions += mergeRun(a, tmp, lo, mid, hi, cmp);
            }
            Object[] swap = a;
            a = tmp;
            tmp = swap;
        }
        return inversions;
    }

    /** Merge {@code [lo,mid)} and {@code [mid,hi)} from {@code src} into {@code dst}, counting inversions. */
    private static long mergeRun(Object[] src, Object[] dst, int lo, int mid, int hi, Comparator<Object> cmp) {
        int i = lo;
        int j = mid;
        int k = lo;
        long inv = 0;
        while (i < mid && j < hi) {
            if (cmp.compare(src[i], src[j]) <= 0) {
                dst[k++] = src[i++];          // left <= right: in order
            } else {
                dst[k++] = src[j++];          // right < every remaining left: (mid - i) inversions
                inv += (mid - i);
            }
        }
        while (i < mid) {
            dst[k++] = src[i++];
        }
        while (j < hi) {
            dst[k++] = src[j++];
        }
        return inv;
    }

    private static Distribution classify(int[] histogram, int n) {
        int buckets = histogram.length;
        double mean = (double) n / buckets;
        double variance = 0.0;
        for (int count : histogram) {
            double d = count - mean;
            variance += d * d;
        }
        variance /= buckets;
        double cv = Math.sqrt(variance) / mean;
        if (cv < 0.30) {
            return Distribution.UNIFORM;
        }
        if (cv < 1.0) {
            return Distribution.SKEWED;
        }
        return Distribution.CLUSTERED;
    }

    /** SplitMix64 finalizer — spreads encoded keys / hash codes for the HLL. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
