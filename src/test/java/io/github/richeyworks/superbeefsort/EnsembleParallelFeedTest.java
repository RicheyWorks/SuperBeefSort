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
 * An empty MIRROR ensemble takes the O(n)/member buildAllFromSorted fast path (each mirror member built
 * concurrently by the ensemble's executor); a non-empty ensemble falls back to median-first add. Either
 * way the resulting logical set must equal the de-duplicated sorted input.
 */
class EnsembleParallelFeedTest {

    private static EnsembleOrderedSet<Integer> ensemble() {
        return EnsembleOrderedSet.builder(Comparator.<Integer>naturalOrder())
                .member(() -> new RedBlackStrategy<Integer>())
                .member(() -> new RedBlackStrategy<Integer>())
                .member(() -> new RedBlackStrategy<Integer>())
                .parallelFanOut()
                .build();
    }

    @Test
    void emptyEnsembleTakesParallelBulkBuildFastPath() {
        Random rng = new Random(7);
        List<Integer> data = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            data.add(rng.nextInt(3000)); // duplicates exercise dedup
        }
        EnsembleOrderedSet<Integer> ensemble = ensemble();

        SortRunResult<Integer> run = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(data)
                .feedMode(FeedMode.PARALLEL)
                .feedInto(ensemble);

        List<Integer> expected = new ArrayList<>(new TreeSet<>(data)); // sorted distinct
        assertEquals(FeedMode.PARALLEL, run.feedResult().mode());
        assertEquals(expected.size(), run.feedResult().inserted(), "inserted == distinct count (bulk path)");
        assertEquals(expected.size(), ensemble.size(), "ensemble size == distinct count");
        assertEquals(expected, ensemble.inOrder(), "ensemble in-order == sorted distinct input");
        assertEquals(expected.get(0), ensemble.minimum());
        assertEquals(expected.get(expected.size() - 1), ensemble.maximum());
    }

    @Test
    void nonEmptyEnsembleFallsBackToBalancedAdd() {
        EnsembleOrderedSet<Integer> ensemble = ensemble();
        ensemble.add(100);
        ensemble.add(200);
        ensemble.add(300);                       // now non-empty -> bulk-build disabled -> add() fallback

        List<Integer> data = new ArrayList<>(List.of(50, 250, 150, 200, 75)); // 200 duplicates an existing key
        BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(data)
                .feedMode(FeedMode.PARALLEL)
                .feedInto(ensemble);

        assertEquals(List.of(50, 75, 100, 150, 200, 250, 300), ensemble.inOrder());
    }
}
