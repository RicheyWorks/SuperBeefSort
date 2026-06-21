package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.core.StrategyId;
import io.github.richeyworks.superbeefsort.registry.StrategyRegistry;
import io.github.richeyworks.superbeefsort.strategy.HeapSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.InPlaceMergeSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.InsertionSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.IntroSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.JdkSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.MergeSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.QuickSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.SortingNetworkStrategy;
import io.github.richeyworks.superbeefsort.strategy.WikiSortStrategy;
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
 * Differential testing: every comparison strategy must agree with the JDK reference sort, both on
 * jqwik-generated random inputs (duplicate-heavy, to stress the three-way partitions) and on a fixed
 * battery of pathological shapes that classically break naive sorts — already sorted, reversed,
 * all-equal, sawtooth, organ-pipe, and few-distinct. {@link SortStrategyPropertyTest} covers the
 * random case; this adds the adversarial structure and an explicit shape battery. Non-comparison
 * sorts (counting/radix) are covered by {@code NonComparisonSortPropertyTest}.
 */
class DifferentialTest {

    private static List<SortStrategy<Integer>> strategies() {
        return List.of(
                new InsertionSortStrategy<>(),
                new SortingNetworkStrategy<>(),
                new MergeSortStrategy<>(),
                new InPlaceMergeSortStrategy<>(),
                new WikiSortStrategy<>(),
                new QuickSortStrategy<>(),
                new HeapSortStrategy<>(),
                new IntroSortStrategy<>(),
                new JdkSortStrategy<>());
    }

    private static void assertAllStrategiesMatchReference(List<Integer> input, String shape) {
        List<Integer> expected = new ArrayList<>(input);
        expected.sort(Comparator.naturalOrder());
        for (SortStrategy<Integer> strategy : strategies()) {
            SortBuffer<Integer> buffer = SortBuffer.of(input, Comparator.<Integer>naturalOrder());
            strategy.sort(buffer, SortContext.noop());
            assertEquals(expected, buffer.toList(),
                    "strategy " + strategy.id() + " disagreed with reference on shape=" + shape + " n=" + input.size());
        }
    }

    @Property(tries = 400)
    void everyStrategyMatchesReferenceOnDuplicateHeavyInput(@ForAll("dupHeavyLists") List<Integer> input) {
        assertAllStrategiesMatchReference(input, "random");
    }

    @Provide
    Arbitrary<List<Integer>> dupHeavyLists() {
        // narrow value range -> many duplicates -> exercises three-way partitioning and tie handling
        return Arbitraries.integers().between(-15, 15).list().ofMaxSize(400);
    }

    @Test
    void everyStrategyMatchesReferenceOnPathologicalShapes() {
        for (int n : new int[] {0, 1, 2, 3, 16, 17, 64, 257, 500}) {
            assertAllStrategiesMatchReference(sorted(n), "sorted");
            assertAllStrategiesMatchReference(reversed(n), "reversed");
            assertAllStrategiesMatchReference(allEqual(n), "all-equal");
            assertAllStrategiesMatchReference(sawtooth(n, 8), "sawtooth");
            assertAllStrategiesMatchReference(organPipe(n), "organ-pipe");
            assertAllStrategiesMatchReference(fewDistinct(n, 4), "few-distinct");
            assertAllStrategiesMatchReference(random(n, 12345 + n), "random");
        }
    }

    // ---- shape generators ----

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
            a.add(7);
        }
        return a;
    }

    private static List<Integer> sawtooth(int n, int period) {
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            a.add(i % period);
        }
        return a;
    }

    private static List<Integer> organPipe(int n) { // 0,1,..,mid,..,1,0 — a "mountain"
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            a.add(Math.min(i, n - 1 - i));
        }
        return a;
    }

    private static List<Integer> fewDistinct(int n, int distinct) {
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            a.add((i * 31 + 7) % distinct);
        }
        return a;
    }

    private static List<Integer> random(int n, long seed) {
        Random rng = new Random(seed);
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            a.add(rng.nextInt(Math.max(1, n)) - n / 2);
        }
        return a;
    }

    // ── radix.lsd.rust differential coverage (only when the kernel module is present) ──────

    private static final StrategyId RUST_RADIX_ID = StrategyId.of("radix.lsd.rust");
    private static final SortStrategy<Integer> RUST_RADIX_STRATEGY = lookupRustRadix();

    private static SortStrategy<Integer> lookupRustRadix() {
        StrategyRegistry registry = StrategyRegistry.withDefaults();
        if (!registry.contains(RUST_RADIX_ID)) {
            return null;
        }
        return registry.get(RUST_RADIX_ID);
    }

    private static void assertRustRadixMatchesReference(List<Integer> input, String shape) {
        if (RUST_RADIX_STRATEGY == null) {
            return; // kernel module not on classpath or native lib absent — skip
        }
        List<Integer> expected = new ArrayList<>(input);
        expected.sort(Comparator.naturalOrder());
        KeyEncoder<Integer> encoder = KeyEncoder.ofInt(i -> i);
        SortBuffer<Integer> buf = SortBuffer.of(input, Comparator.<Integer>naturalOrder(), encoder);
        RUST_RADIX_STRATEGY.sort(buf, SortContext.noop());
        assertEquals(expected, buf.toList(),
                "radix.lsd.rust disagreed on shape=" + shape + " n=" + input.size());
    }

    @Test
    void rustRadixMatchesReferenceOnPathologicalShapes() {
        if (RUST_RADIX_STRATEGY == null) {
            return;
        }
        for (int n : new int[] {0, 1, 2, 3, 16, 17, 64, 257, 500, 2000}) {
            assertRustRadixMatchesReference(sorted(n),           "sorted");
            assertRustRadixMatchesReference(reversed(n),         "reversed");
            assertRustRadixMatchesReference(allEqual(n),         "all-equal");
            assertRustRadixMatchesReference(sawtooth(n, 8),      "sawtooth");
            assertRustRadixMatchesReference(organPipe(n),        "organ-pipe");
            assertRustRadixMatchesReference(fewDistinct(n, 4),   "few-distinct");
            assertRustRadixMatchesReference(random(n, 99L + n),  "random");
        }
    }

    @Property(tries = 300)
    void rustRadixMatchesReferenceOnRandomInput(@ForAll("boundedIntsForRust") List<Integer> input) {
        assertRustRadixMatchesReference(input, "property-random");
    }

    @Provide
    Arbitrary<List<Integer>> boundedIntsForRust() {
        return Arbitraries.integers().between(-5000, 5000).list().ofMaxSize(500);
    }
}
