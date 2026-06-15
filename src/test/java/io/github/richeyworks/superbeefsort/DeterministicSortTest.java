package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.engine.JobSpec;
import io.github.richeyworks.superbeefsort.strategy.QuickSortStrategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic mode: with a seed in the {@link SortContext}, the otherwise-randomized
 * {@link QuickSortStrategy} pivot becomes reproducible, so the exact comparison and move counts repeat
 * run-to-run — what you want for golden tests, debugging, and stable benchmarks. Without a seed the
 * default (unpredictable) behaviour is unchanged.
 */
class DeterministicSortTest {

    private static List<Integer> randomInput(int n, long seed) {
        Random rng = new Random(seed);
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            a.add(rng.nextInt(n / 2)); // some duplicates -> exercises the three-way partition
        }
        return a;
    }

    /** Run quicksort with the given seed; return {comparisons, moves} and assert it sorted correctly. */
    private static long[] runSeeded(List<Integer> input, long seed) {
        SortBuffer<Integer> b = SortBuffer.of(input, Comparator.<Integer>naturalOrder());
        new QuickSortStrategy<Integer>().sort(b, SortContext.deterministic(seed));
        List<Integer> expected = new ArrayList<>(input);
        expected.sort(Comparator.naturalOrder());
        assertEquals(expected, b.toList(), "deterministic quicksort must still sort correctly");
        return new long[] {b.comparisons(), b.moves()};
    }

    @Test
    void sameSeedReproducesExactCountsAndOrder() {
        List<Integer> input = randomInput(3000, 42);
        long[] first = runSeeded(input, 7L);
        long[] second = runSeeded(input, 7L);
        assertEquals(first[0], second[0], "comparisons must be identical for the same seed");
        assertEquals(first[1], second[1], "moves must be identical for the same seed");
    }

    @Test
    void differentSeedsDriveDifferentPivotSequences() {
        List<Integer> input = randomInput(3000, 99);
        Set<Long> distinctComparisonCounts = new HashSet<>();
        for (long seed : new long[] {1L, 2L, 3L, 4L, 5L}) {
            distinctComparisonCounts.add(runSeeded(input, seed)[0]);
        }
        // The pivot is genuinely seed-driven, so at least some seeds must yield different comparison counts.
        assertTrue(distinctComparisonCounts.size() > 1,
                "different seeds should produce different pivot sequences (got identical counts: "
                        + distinctComparisonCounts + ")");
    }

    @Test
    void defaultContextHasNoSeedAndStillSorts() {
        List<Integer> input = randomInput(500, 5);
        SortBuffer<Integer> b = SortBuffer.of(input, Comparator.<Integer>naturalOrder());
        new QuickSortStrategy<Integer>().sort(b, SortContext.noop());
        List<Integer> expected = new ArrayList<>(input);
        expected.sort(Comparator.naturalOrder());
        assertEquals(expected, b.toList());
        assertTrue(SortContext.noop().randomSeed().isEmpty(), "noop context carries no seed");
    }

    @Test
    void seedThreadsThroughContextAndJobSpec() {
        assertEquals(OptionalLong.of(42L), SortContext.deterministic(42L).randomSeed());
        assertEquals(OptionalLong.of(7L), JobSpec.defaults().withRandomSeed(7L).randomSeed());
        assertTrue(JobSpec.defaults().randomSeed().isEmpty(), "default JobSpec is non-deterministic");
        // derived specs preserve the seed
        assertEquals(OptionalLong.of(7L),
                JobSpec.defaults().withRandomSeed(7L).withPolicy(io.github.richeyworks.superbeefsort.select.SelectionPolicy.STABLE).randomSeed());
        assertFalse(JobSpec.defaults().withRandomSeed(7L).randomSeed().isEmpty());
    }
}
