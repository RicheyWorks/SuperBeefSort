package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.ensemble.EnsembleOrderedSet;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.superbeefsort.engine.SortRunResult;
import io.github.richeyworks.superbeefsort.feed.FeedMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 3: SuperBeefSort sorts, then feeds a parallel-fan-out N-version ensemble via FeedMode.PARALLEL.
 * The three mirror members build concurrently (the ensemble's own MemberExecutor); the resulting logical
 * set must be exactly the de-duplicated sorted input.
 */
class EnsembleParallelFeedTest {

    @Test
    void feedsParallelFanOutEnsembleInOrder() {
        Random rng = new Random(7);
        List<Integer> data = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            data.add(rng.nextInt(3000)); // duplicates exercise add()'s dedup
        }

        EnsembleOrderedSet<Integer> ensemble = EnsembleOrderedSet.builder(Comparator.<Integer>naturalOrder())
                .member(() -> new RedBlackStrategy<Integer>())
                .member(() -> new RedBlackStrategy<Integer>())
                .member(() -> new RedBlackStrategy<Integer>())
                .parallelFanOut()
                .build();

        SortRunResult<Integer> run = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(data)
                .feedMode(FeedMode.PARALLEL)
                .feedInto(ensemble);

        List<Integer> expected = new ArrayList<>(new TreeSet<>(data)); // sorted distinct
        assertEquals(FeedMode.PARALLEL, run.feedResult().mode());
        assertEquals(expected.size(), ensemble.size(), "ensemble size == distinct count");
        assertEquals(expected, ensemble.inOrder(), "ensemble in-order == sorted distinct input");
        assertEquals(expected.get(0), ensemble.minimum());
        assertEquals(expected.get(expected.size() - 1), ensemble.maximum());
    }
}
