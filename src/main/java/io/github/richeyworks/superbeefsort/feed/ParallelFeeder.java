package io.github.richeyworks.superbeefsort.feed;

import java.util.List;

/**
 * The parallel feed personality: median-first balanced insertion (the order that minimizes each
 * red-black member's rotations), aimed at an {@link io.github.richeyworks.csrbt.ensemble.EnsembleOrderedSet}
 * built with {@code parallelFanOut()}. There a single logical {@code add(K)} is fanned out to all mirror
 * members concurrently by the ensemble's own {@code MemberExecutor} (ADR-003 E5) under its one writer
 * lock — so the K member builds overlap with no SuperBeefSort-side threads and no CSRBT changes. Against a
 * single {@code OrderedSet} (or a sequential ensemble) it behaves exactly like {@link BalancedBuildFeeder},
 * just reported as {@link FeedMode#PARALLEL}.
 */
public final class ParallelFeeder<K> implements SortFeeder<K> {

    @Override
    public FeedResult feed(List<K> sortedRun, CsrbtTarget<K> target) {
        long start = System.nanoTime();
        int inserted = BalancedBuildFeeder.insertBalanced(sortedRun, target);
        long elapsed = System.nanoTime() - start;
        int duplicates = sortedRun.size() - inserted;
        return new FeedResult(FeedMode.PARALLEL, sortedRun.size(), inserted, duplicates, 0, true, elapsed);
    }

    @Override
    public FeedMode mode() {
        return FeedMode.PARALLEL;
    }
}
