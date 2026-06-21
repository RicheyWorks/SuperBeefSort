package io.github.richeyworks.sbskernels.rust;

import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Validates {@link RustRadixSortStrategy} against the JDK reference sort across the same
 * pathological shapes the main {@code DifferentialTest} uses for comparison sorts.
 * Skipped automatically when the native bridge is unavailable (JDK &lt; 22 or missing cdylib).
 */
class RustRadixDifferentialTest {

    private final RustRadixSortStrategy<Integer> strategy = new RustRadixSortStrategy<>();
    private final Comparator<Integer> cmp = Comparator.naturalOrder();
    private final KeyEncoder<Integer> encoder = KeyEncoder.ofInt(i -> i);

    private void assertMatchesReference(List<Integer> input, String shape) {
        Assumptions.assumeTrue(RustRadixBridge.isAvailable(),
                "Native bridge unavailable — skipping Rust differential test");
        List<Integer> expected = new ArrayList<>(input);
        expected.sort(cmp);
        SortBuffer<Integer> buf = SortBuffer.of(input, cmp, encoder);
        strategy.sort(buf, SortContext.noop());
        assertEquals(expected, buf.toList(),
                "radix.lsd.rust disagreed on shape=" + shape + " n=" + input.size());
    }

    @Test
    void pathologicalShapes() {
        for (int n : new int[] {0, 1, 2, 3, 16, 17, 64, 257, 500, 2000, 10_000}) {
            assertMatchesReference(sorted(n),            "sorted");
            assertMatchesReference(reversed(n),          "reversed");
            assertMatchesReference(allEqual(n),          "all-equal");
            assertMatchesReference(sawtooth(n, 8),       "sawtooth");
            assertMatchesReference(organPipe(n),         "organ-pipe");
            assertMatchesReference(fewDistinct(n, 4),    "few-distinct");
            assertMatchesReference(random(n, 777L + n),  "random");
        }
    }

    @Test
    void negativeKeysAndExtremes() {
        Assumptions.assumeTrue(RustRadixBridge.isAvailable());
        List<Integer> extremes = List.of(Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE, -1000, 1000);
        assertMatchesReference(extremes, "extremes");
    }

    @Test
    void narrowHighMagnitudeRange() {
        Assumptions.assumeTrue(RustRadixBridge.isAvailable());
        // All keys in [1_000_000, 1_001_000]: should sort in 1 pass after offset-by-min
        List<Integer> keys = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            keys.add(1_000_000 + (i * 997) % 1001);
        }
        assertMatchesReference(keys, "narrow-high-magnitude");
    }

    @Test
    void stability() {
        Assumptions.assumeTrue(RustRadixBridge.isAvailable());
        // Keys in [-5, 5]: many duplicates; sorted order must match stable JDK reference
        List<Integer> keys = new ArrayList<>();
        Random rng = new Random(42);
        for (int i = 0; i < 1000; i++) {
            keys.add(rng.nextInt(11) - 5);
        }
        assertMatchesReference(keys, "stability");
    }

    // ── Shape generators ────────────────────────────────────────────────────────────────────

    private static List<Integer> sorted(int n) {
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) a.add(i);
        return a;
    }

    private static List<Integer> reversed(int n) {
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) a.add(n - i);
        return a;
    }

    private static List<Integer> allEqual(int n) {
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) a.add(7);
        return a;
    }

    private static List<Integer> sawtooth(int n, int period) {
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) a.add(i % period);
        return a;
    }

    private static List<Integer> organPipe(int n) {
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) a.add(Math.min(i, n - 1 - i));
        return a;
    }

    private static List<Integer> fewDistinct(int n, int distinct) {
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) a.add((i * 31 + 7) % distinct);
        return a;
    }

    private static List<Integer> random(int n, long seed) {
        Random rng = new Random(seed);
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) a.add(rng.nextInt(Math.max(1, n)) - n / 2);
        return a;
    }
}
