package io.github.richeyworks.superbeefsort.feed;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * The most defensive feeder: inserts the sorted run median-first (a balanced shape, like
 * {@link BalancedBuildFeeder}) but validates CSRBT's health after EVERY insert and accounts for
 * duplicates explicitly. It is the slowest feeder, but it surfaces a health regression at the exact
 * insert that caused it -- the "precision pill-robot" personality from the architecture (sec 5.4 /
 * 6.5). If the target exposes no health hook (e.g. an ensemble) it still inserts correctly and
 * reports zero checks, exactly like the health-gated feeder.
 *
 * <p>{@code healthy} latches false the moment any post-insert check fails, so the report tells you
 * whether the tree stayed valid throughout the whole feed, not just at the end.</p>
 */
public final class PrecisionFeeder<K> implements SortFeeder<K> {

    @Override
    public FeedResult feed(List<K> sortedRun, CsrbtTarget<K> target) {
        long start = System.nanoTime();
        int n = sortedRun.size();
        boolean canCheck = target.supportsHealthCheck();
        int inserted = 0;
        int duplicates = 0;
        int checks = 0;
        boolean healthy = true;

        // Median-first (pre-order) traversal of the sorted run, validating after each insert. Iterative
        // so a very large run cannot overflow the stack (mirrors BalancedBuildFeeder's traversal).
        Deque<int[]> stack = new ArrayDeque<>();
        if (n > 0) {
            stack.push(new int[]{0, n - 1});
        }
        while (!stack.isEmpty()) {
            int[] range = stack.pop();
            int lo = range[0];
            int hi = range[1];
            if (lo > hi) {
                continue;
            }
            int mid = lo + ((hi - lo) >>> 1);
            if (target.add(sortedRun.get(mid))) {
                inserted++;
            } else {
                duplicates++; // explicit: a key already present is counted, never silently dropped
            }
            if (canCheck) {
                checks++;
                healthy = target.checkHealth() && healthy; // validate every insert; latch unhealthy
            }
            stack.push(new int[]{mid + 1, hi});
            stack.push(new int[]{lo, mid - 1});
        }

        long elapsed = System.nanoTime() - start;
        return new FeedResult(FeedMode.PRECISION, n, inserted, duplicates, checks, healthy, elapsed);
    }

    @Override
    public FeedMode mode() {
        return FeedMode.PRECISION;
    }
}
