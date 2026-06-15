package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.profile.Distribution;
import io.github.richeyworks.superbeefsort.profile.IntelligentDataProfiler;
import io.github.richeyworks.superbeefsort.profile.ProfileDepth;
import io.github.richeyworks.superbeefsort.registry.StrategyRegistry;
import io.github.richeyworks.superbeefsort.select.CostModelStrategySelector;
import io.github.richeyworks.superbeefsort.select.RuleBasedStrategySelector;
import io.github.richeyworks.superbeefsort.select.SelectionPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The profiler's global inversion signal and the selector routing it enables. Inversions are a true
 * global disorder measure (out-of-order pairs), distinct from the adjacent-pair {@code sortednessRatio}:
 * they are exact for small/DEEP inputs and a strided-sample estimate otherwise, and they let the
 * selectors send genuinely-few-inversion inputs to adaptive insertion sort while never doing so on an
 * estimate (which could hide an O(n^2) trap).
 */
class InversionCountTest {

    private static DataProfile profile(int[] a, ProfileDepth depth) {
        List<Integer> list = new ArrayList<>(a.length);
        for (int x : a) {
            list.add(x);
        }
        SortBuffer<Integer> b = SortBuffer.of(list, Comparator.<Integer>naturalOrder());
        return new IntelligentDataProfiler<Integer>().profile(b, depth);
    }

    private static int[] sortedAsc(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = i;
        }
        return a;
    }

    private static long bruteForceInversions(int[] a) {
        long inv = 0;
        for (int i = 0; i < a.length; i++) {
            for (int j = i + 1; j < a.length; j++) {
                if (a[i] > a[j]) {
                    inv++;
                }
            }
        }
        return inv;
    }

    // -- profiler: exact counts --

    @Test
    void sortedInputHasZeroInversions() {
        DataProfile p = profile(sortedAsc(100), ProfileDepth.SHALLOW);
        assertTrue(p.inversionsExact(), "n=100 is below the exact threshold");
        assertEquals(0L, p.inversions());
        assertEquals(0.0, p.inversionRatio(), 1e-12);
    }

    @Test
    void reversedInputHasMaximumInversions() {
        int n = 100;
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = n - 1 - i;
        }
        DataProfile p = profile(a, ProfileDepth.SHALLOW);
        assertEquals((long) n * (n - 1) / 2, p.inversions(), "reversed = every pair inverted = C(n,2)");
        assertEquals(p.maxInversions(), p.inversions());
        assertEquals(1.0, p.inversionRatio(), 1e-12);
    }

    @Test
    void singleAdjacentSwapIsOneInversion() {
        int[] a = sortedAsc(100);
        int t = a[0];
        a[0] = a[1];
        a[1] = t; // [1,0,2,3,...]
        assertEquals(1L, profile(a, ProfileDepth.SHALLOW).inversions());
    }

    @Test
    void oneElementMovedFarCountsEveryStraddledPair() {
        // Move the largest value to the front: [99, 0, 1, ..., 98]. It now precedes all 99 smaller
        // values -> exactly 99 inversions, yet 98/99 adjacent pairs are still in order (high adjacency).
        int n = 100;
        int[] a = new int[n];
        a[0] = n - 1;
        for (int i = 1; i < n; i++) {
            a[i] = i - 1;
        }
        DataProfile p = profile(a, ProfileDepth.SHALLOW);
        assertEquals(n - 1, p.inversions());
        assertTrue(p.sortednessRatio() > 0.95, "adjacency stays high even though one element is far out of place");
    }

    @Test
    void exactCountMatchesBruteForceOnAShuffle() {
        Random rng = new Random(20260615L);
        int[] a = sortedAsc(200);
        for (int i = a.length - 1; i > 0; i--) { // Fisher-Yates
            int j = rng.nextInt(i + 1);
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
        DataProfile p = profile(a, ProfileDepth.SHALLOW);
        assertTrue(p.inversionsExact());
        assertEquals(bruteForceInversions(a), p.inversions());
    }

    // -- profiler: sampled estimate on large inputs --

    @Test
    void largeInputIsEstimatedNotExact() {
        int[] a = sortedAsc(100_000);
        DataProfile p = profile(a, ProfileDepth.SHALLOW);
        assertFalse(p.inversionsExact(), "above the exact threshold the SHALLOW profile samples");
        assertEquals(0L, p.inversions(), "a sorted sample estimates zero inversions");
    }

    @Test
    void deepProfileIsExactEvenOnLargeInputs() {
        int n = 100_000;
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = n - 1 - i; // reversed
        }
        DataProfile p = profile(a, ProfileDepth.DEEP);
        assertTrue(p.inversionsExact(), "DEEP scans every element");
        assertEquals((long) n * (n - 1) / 2, p.inversions());
    }

    @Test
    void estimateTracksTheExactRatioOnARandomLargeInput() {
        Random rng = new Random(42L);
        int n = 100_000;
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = rng.nextInt();
        }
        double estimated = profile(a, ProfileDepth.SHALLOW).inversionRatio(); // sampled
        double exact = profile(a, ProfileDepth.DEEP).inversionRatio();         // ground truth
        assertEquals(0.5, exact, 0.05, "a random permutation is ~half inverted");
        assertEquals(exact, estimated, 0.05, "the strided-sample estimate should track the exact ratio");
    }

    // -- DataProfile helpers --

    @Test
    void ratioFallsBackToAdjacencyWhenUnmeasured() {
        DataProfile p = new DataProfile(100, 0.7, false, ProfileDepth.SHALLOW, 100, null,
                Distribution.UNKNOWN, 50); // 8-arg back-compat: inversions unmeasured
        assertFalse(p.inversionsMeasured());
        assertEquals(0.30, p.inversionRatio(), 1e-12, "unmeasured -> 1 - sortednessRatio proxy");
    }

    // -- selector routing --

    private static DataProfile built(int n, double sortedness, int longestRun, long inversions, boolean exact) {
        return new DataProfile(n, sortedness, false, ProfileDepth.SHALLOW, n, null,
                Distribution.UNKNOWN, longestRun, inversions, exact);
    }

    private final RuleBasedStrategySelector ruleSelector = new RuleBasedStrategySelector();
    private final CostModelStrategySelector costSelector = new CostModelStrategySelector();
    private final StrategyRegistry registry = StrategyRegistry.withDefaults();

    private String rulePick(DataProfile p) {
        return ruleSelector.select(p, SelectionPolicy.SMART, registry).strategy().value();
    }

    private String costPick(DataProfile p) {
        return costSelector.select(p, SelectionPolicy.SMART, registry).strategy().value();
    }

    @Test
    void ruleSelectorPicksInsertionForFewExactInversions() {
        // n=1000, almost fully ordered, only 10 inversions, measured exactly -> adaptive insertion.
        assertEquals("insertion", rulePick(built(1000, 1.0, 1000, 10L, true)));
    }

    @Test
    void ruleSelectorAvoidsInsertionWhenInversionsAreManyDespiteHighAdjacency() {
        // High adjacency (0.95 -> "nearly sorted" by the old signal) but 200k inversions: insertion would
        // be O(n^2)-ish. The exact count reveals it, so we route to run-aware TimSort instead.
        assertEquals("jdk.timsort", rulePick(built(1000, 0.95, 500, 200_000L, true)));
    }

    @Test
    void ruleSelectorNeverTrustsAnEstimateForInsertion() {
        // Same few-inversions shape, but NOT exact: the insertion fast-path must not fire on an estimate.
        assertNotEquals("insertion", rulePick(built(50_000, 1.0, 50_000, 10L, false)));
    }

    @Test
    void costModelPicksInsertionForFewExactInversions() {
        // n=2000, 50 inversions exact: n+inversions (~2050) beats n log n (~22000) and TimSort.
        assertEquals("insertion", costPick(built(2000, 0.5, 1, 50L, true)));
    }

    @Test
    void costModelAvoidsInsertionForManyInversions() {
        // 500k inversions: insertion cost (~502000) loses to introsort (~22000).
        assertNotEquals("insertion", costPick(built(2000, 0.5, 1, 500_000L, true)));
    }
}
