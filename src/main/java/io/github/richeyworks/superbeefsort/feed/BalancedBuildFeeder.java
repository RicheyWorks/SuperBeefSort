package io.github.richeyworks.superbeefsort.feed;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Feeds a sorted run by inserting the median of each subrange first (pre-order over the sorted
 * array). Inserting a sorted sequence in this order yields a balanced tree shape and minimizes the
 * rotations CSRBT must perform — the Phase 0 stand-in for an O(n) bulk build, since CSRBT exposes
 * only {@code add(K)}. The traversal is iterative so very large runs do not overflow the stack.
 */
public final class BalancedBuildFeeder<K> implements SortFeeder<K> {

    @Override
    public FeedResult feed(List<K> sortedRun, CsrbtTarget<K> target) {
        long start = System.nanoTime();
        int inserted = insertBalanced(sortedRun, target);
        long elapsed = System.nanoTime() - start;
        int duplicates = sortedRun.size() - inserted;
        return new FeedResult(FeedMode.BALANCED, sortedRun.size(), inserted, duplicates, 0, true, elapsed);
    }

    /** Insert {@code run} (assumed sorted) in median-first order. Returns the number actually added. */
    static <K> int insertBalanced(List<K> run, CsrbtTarget<K> target) {
        int inserted = 0;
        Deque<int[]> stack = new ArrayDeque<>();
        if (!run.isEmpty()) {
            stack.push(new int[]{0, run.size() - 1});
        }
        while (!stack.isEmpty()) {
            int[] range = stack.pop();
            int lo = range[0];
            int hi = range[1];
            if (lo > hi) {
                continue;
            }
            int mid = lo + ((hi - lo) >>> 1);
            if (target.add(run.get(mid))) {
                inserted++;
            }
            stack.push(new int[]{mid + 1, hi});
            stack.push(new int[]{lo, mid - 1});
        }
        return inserted;
    }

    @Override
    public FeedMode mode() {
        return FeedMode.BALANCED;
    }
}
