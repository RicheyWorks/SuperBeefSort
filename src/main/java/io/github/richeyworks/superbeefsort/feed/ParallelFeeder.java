package io.github.richeyworks.superbeefsort.feed;

import java.util.List;

/**
 * The parallel feed personality for an {@link io.github.richeyworks.csrbt.ensemble.EnsembleOrderedSet}
 * built with {@code parallelFanOut()}. When the ensemble is empty and in an all-exact mode it takes the
 * O(n)/member fast path — dedup once, then fan {@code buildFromSorted} out to every mirror member
 * concurrently via the ensemble's own {@code MemberExecutor} (ADR-003 E5), no SuperBeefSort threads.
 * Otherwise (non-empty / non-mirror ensemble, or a single {@code OrderedSet}) it falls back to the
 * median-first balanced {@code add} of {@link BalancedBuildFeeder}. Either way it reports
 * {@link FeedMode#PARALLEL}.
 */
public final class ParallelFeeder<K> implements SortFeeder<K> {

    @Override
    public FeedResult feed(List<K> sortedRun, CsrbtTarget<K> target) {
        long start = System.nanoTime();
        if (target.supportsEnsembleBulkBuild()) {
            List<K> distinct = BulkBuildFeeder.dedupSorted(sortedRun, target.comparator());
            target.ensembleBulkBuild(distinct);
            long elapsed = System.nanoTime() - start;
            return new FeedResult(FeedMode.PARALLEL, sortedRun.size(), distinct.size(),
                    sortedRun.size() - distinct.size(), 0, true, elapsed);
        }
        int inserted = BalancedBuildFeeder.insertBalanced(sortedRun, target);
        long elapsed = System.nanoTime() - start;
        return new FeedResult(FeedMode.PARALLEL, sortedRun.size(), inserted,
                sortedRun.size() - inserted, 0, true, elapsed);
    }

    @Override
    public FeedMode mode() {
        return FeedMode.PARALLEL;
    }
}
