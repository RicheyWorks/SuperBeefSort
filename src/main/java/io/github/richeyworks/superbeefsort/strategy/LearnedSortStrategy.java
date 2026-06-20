package io.github.richeyworks.superbeefsort.strategy;

import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.core.StrategyCapabilities;
import io.github.richeyworks.superbeefsort.core.StrategyId;

import java.util.Arrays;
import java.util.Comparator;

/**
 * A learned (sample) sort: it <em>learns</em> bucket boundaries from the data's own distribution, then
 * sorts within each small bucket. A short, oversampled, sorted sample of the encoded keys yields the
 * empirical CDF's quantiles, which become the bucket splitters — so the buckets stay balanced even on
 * skewed or clustered data, where a fixed equi-range bucketing would pile everything into a few buckets.
 * Each element is placed into its bucket by a binary search over the splitters (no key comparisons), then
 * each bucket is sorted with the real comparator; concatenating the sorted buckets is fully ordered
 * because an order-faithful {@link KeyEncoder} guarantees every key in bucket {@code i} precedes every key
 * in bucket {@code i+1}.
 *
 * <p>This is the engine's distribution-adaptive, near-linear sort: when the learned model fits, the
 * metered comparison count is a fraction of a comparison sort's {@code n log n} (the within-bucket sorts
 * touch only ~constant-size buckets); when it doesn't (e.g. all-equal keys) it degrades gracefully to a
 * single comparator sort and stays correct. Requires a {@link KeyEncoder}; stable; out of place.</p>
 */
public final class LearnedSortStrategy<K> implements SortStrategy<K> {

    public static final StrategyId ID = StrategyId.of("learned");

    private static final int TARGET_BUCKET = 64;     // aim for ~this many keys per bucket
    private static final int MAX_BUCKETS = 1 << 12;  // cap the splitter array
    private static final int OVERSAMPLE = 8;         // sample this many keys per bucket -> balanced splitters

    @Override
    @SuppressWarnings("unchecked")
    public void sort(SortBuffer<K> b, SortContext context) {
        int n = b.size();
        if (n < 2) {
            return;
        }
        KeyEncoder<K> encoder = b.keyEncoder();
        if (encoder == null) {
            throw new IllegalStateException("LearnedSortStrategy requires a KeyEncoder on the buffer");
        }

        long[] keys = new long[n];
        Object[] items = new Object[n];
        for (int i = 0; i < n; i++) {
            Object item = b.get(i);
            items[i] = item;
            keys[i] = encoder.encodeKey((K) item);
        }

        int buckets = Math.max(2, Math.min(MAX_BUCKETS, n / TARGET_BUCKET));
        long[] splitters = learnSplitters(keys, n, buckets);

        // bucket each element (binary search over splitters — no metered comparisons), then count + prefix-sum
        int[] start = new int[buckets + 1];
        int[] bucketOf = new int[n];
        for (int i = 0; i < n; i++) {
            int bk = bucketIndex(splitters, keys[i]);
            bucketOf[i] = bk;
            start[bk + 1]++;
        }
        for (int bk = 0; bk < buckets; bk++) {
            start[bk + 1] += start[bk];
        }

        // stable scatter into aux by bucket (i ascending + cursor++ keeps equal elements in input order)
        Object[] aux = new Object[n];
        int[] cursor = Arrays.copyOf(start, buckets);
        b.recordAux(28L * n); // long keys[n] + Object items[n] + int bucketOf[n] + Object aux[n] (+ O(buckets))
        for (int i = 0; i < n; i++) {
            int bk = bucketOf[i];
            aux[cursor[bk]++] = items[i];
        }

        // sort each bucket with the real (metered) comparator — stable TimSort; correct for any splitters
        Comparator<Object> cmp = (x, y) -> b.compareValues((K) x, (K) y);
        for (int bk = 0; bk < buckets; bk++) {
            int from = start[bk];
            int to = start[bk + 1];
            if (to - from > 1) {
                Arrays.sort(aux, from, to, cmp);
            }
        }

        for (int i = 0; i < n; i++) {
            b.set(i, (K) aux[i]);
            b.recordMove();
        }
    }

    /** Learn bucket boundaries: the quantiles of an oversampled, sorted sample of the keys (the empirical CDF). */
    private static long[] learnSplitters(long[] keys, int n, int buckets) {
        int sampleSize = Math.min(n, buckets * OVERSAMPLE);
        long[] sample = new long[sampleSize];
        for (int k = 0; k < sampleSize; k++) {
            sample[k] = keys[(int) ((long) k * n / sampleSize)]; // strided, deterministic
        }
        Arrays.sort(sample);
        long[] splitters = new long[buckets - 1];
        for (int s = 0; s < buckets - 1; s++) {
            splitters[s] = sample[(int) ((long) (s + 1) * sampleSize / buckets)];
        }
        return splitters;
    }

    /** Bucket index = number of splitters {@code <= key}, in {@code [0, splitters.length]}. */
    private static int bucketIndex(long[] splitters, long key) {
        int lo = 0;
        int hi = splitters.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (splitters[mid] <= key) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    @Override
    public StrategyCapabilities capabilities() {
        return StrategyCapabilities.builder()
                .stable(true).inPlace(false).comparisonBased(true).adaptive(true).requiresIntegerKeys(true)
                .auxMemory(StrategyCapabilities.AuxMemory.LINEAR).build();   // O(n) bucket arrays
    }

    @Override
    public StrategyId id() {
        return ID;
    }
}
