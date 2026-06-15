package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.strategy.LearnedSortStrategy;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The learned (sample) sort must match the reference sort for every input — including the distributions
 * that decide whether its learned bucket boundaries actually balance: uniform, sorted, reversed,
 * all-equal, clustered, skewed, and duplicate-heavy, plus negatives (handled by the signed key order,
 * no sign-flip needed). Correctness holds for any splitters because each bucket is finally sorted with
 * the real comparator; this test pins that.
 */
class LearnedSortPropertyTest {

    private static final KeyEncoder<Integer> ENCODER = KeyEncoder.ofInt(i -> i);

    private static void assertLearnedMatchesReference(List<Integer> input, String shape) {
        List<Integer> expected = new ArrayList<>(input);
        expected.sort(Comparator.naturalOrder());
        SortBuffer<Integer> buffer = SortBuffer.of(input, Comparator.<Integer>naturalOrder(), ENCODER);
        new LearnedSortStrategy<Integer>().sort(buffer, SortContext.noop());
        assertEquals(expected, buffer.toList(), "learned sort on shape=" + shape + " n=" + input.size());
    }

    @Property(tries = 400)
    void learnedSortMatchesReferenceOnBoundedInts(@ForAll("boundedInts") List<Integer> input) {
        assertLearnedMatchesReference(input, "random");
    }

    @Provide
    Arbitrary<List<Integer>> boundedInts() {
        return Arbitraries.integers().between(-1000, 1000).list().ofMaxSize(600); // includes negatives + duplicates
    }

    @Test
    void learnedSortMatchesReferenceAcrossDistributions() {
        for (int n : new int[] {0, 1, 2, 3, 100, 1000, 5000}) {
            assertLearnedMatchesReference(uniform(n, 1), "uniform");
            assertLearnedMatchesReference(sorted(n), "sorted");
            assertLearnedMatchesReference(reversed(n), "reversed");
            assertLearnedMatchesReference(allEqual(n), "all-equal");
            assertLearnedMatchesReference(clustered(n, 2), "clustered");
            assertLearnedMatchesReference(skewed(n, 3), "skewed");
            assertLearnedMatchesReference(dupHeavy(n), "dup-heavy");
            assertLearnedMatchesReference(withNegatives(n, 4), "negatives");
        }
    }

    // ---- distribution generators ----

    private static List<Integer> uniform(int n, long seed) {
        Random rng = new Random(seed);
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            a.add(rng.nextInt());
        }
        return a;
    }

    private static List<Integer> sorted(int n) {
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            a.add(i);
        }
        return a;
    }

    private static List<Integer> reversed(int n) {
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            a.add(n - i);
        }
        return a;
    }

    private static List<Integer> allEqual(int n) {
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            a.add(42);
        }
        return a;
    }

    private static List<Integer> clustered(int n, long seed) { // a few tight clusters far apart
        Random rng = new Random(seed);
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            a.add(rng.nextInt(5) * 1_000_000 + rng.nextInt(50));
        }
        return a;
    }

    private static List<Integer> skewed(int n, long seed) { // squaring a uniform draw -> heavy low tail
        Random rng = new Random(seed);
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int u = rng.nextInt(1000);
            a.add(u * u);
        }
        return a;
    }

    private static List<Integer> dupHeavy(int n) {
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            a.add((i * 7) % 12);
        }
        return a;
    }

    private static List<Integer> withNegatives(int n, long seed) {
        Random rng = new Random(seed);
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            a.add(rng.nextInt(2_000_000) - 1_000_000);
        }
        return a;
    }
}
