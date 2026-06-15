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

    @Override
    public DataProfile profile(SortBuffer<K> b, ProfileDepth depth) {
        int n = b.size();
        if (n < 2) {
            return new DataProfile(n, 1.0, false, depth, n, null, Distribution.UNKNOWN, n);
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

        KeyEncoder<K> encoder = b.keyEncoder();
        if (encoder == null) {
            Hll hll = new Hll();
            for (int i = 0; i < n; i++) {
                K v = b.get(i);
                hll.add(mix(v == null ? 0L : v.hashCode()));
            }
            return new DataProfile(n, ratio, duplicates, depth, hll.estimate(), null, Distribution.UNKNOWN, longestRun);
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
            return new DataProfile(n, ratio, duplicates, depth, hll.estimate(), null, Distribution.UNKNOWN, longestRun);
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

        return new DataProfile(n, ratio, duplicates, depth, hll.estimate(), keyStats, distribution, longestRun);
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
