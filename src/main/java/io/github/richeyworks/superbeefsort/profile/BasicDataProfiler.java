package io.github.richeyworks.superbeefsort.profile;

import io.github.richeyworks.superbeefsort.core.SortBuffer;

/**
 * Lightweight profiler: a single linear pass measuring how close to sorted the input already is and
 * whether adjacent duplicates appear. It does not compute key stats or distinct counts — use
 * {@link IntelligentDataProfiler} for the full Phase 1 analysis.
 */
public final class BasicDataProfiler<K> implements DataProfiler<K> {

    @Override
    public DataProfile profile(SortBuffer<K> b, ProfileDepth depth) {
        int n = b.size();
        if (n < 2) {
            return new DataProfile(n, 1.0, false, depth, n, null, Distribution.UNKNOWN);
        }
        long inOrder = 0;
        long pairs = 0;
        boolean duplicates = false;
        for (int i = 1; i < n; i++) {
            int c = b.compare(i - 1, i);
            if (c <= 0) {
                inOrder++;
            }
            if (c == 0) {
                duplicates = true;
            }
            pairs++;
        }
        double ratio = (double) inOrder / pairs;
        return new DataProfile(n, ratio, duplicates, depth, -1, null, Distribution.UNKNOWN);
    }
}
