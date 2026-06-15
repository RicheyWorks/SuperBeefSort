package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.strategy.HeapSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.InsertionSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.IntroSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.JdkSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.MergeSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.QuickSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.SortingNetworkStrategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chaos mode: adversarial inputs that prove the robustness claims hold under attack.
 *
 * <p>The headline claim of {@link IntroSortStrategy} is that its recursion-depth guard falls back to
 * heapsort, so it stays O(n log n) even on inputs engineered to make quicksort go quadratic. This test
 * <em>constructs</em> such an input with the Bentley–McIlroy "median-of-three killer" adversary — a
 * comparator that lazily assigns values so the chosen pivot is always near-extreme — then shows the
 * contrast on the very same array: a plain (unguarded) median-of-three quicksort does &gt; n²/5
 * comparisons, while the engine's introsort stays well under 8·n·log₂n and still sorts correctly. The
 * gap <em>is</em> the depth guard firing.</p>
 *
 * <p>The adversary construction and these bounds were cross-checked against a faithful JS model of the
 * engine's introsort before being pinned here (plain ≈ n²/4, intro ≈ 3.5·n·log₂n).</p>
 */
class ChaosTest {

    /** All comparison strategies (same set as {@link SortStrategyPropertyTest}); excludes counting/radix. */
    private static List<SortStrategy<Integer>> comparisonStrategies() {
        return List.of(
                new InsertionSortStrategy<>(),
                new SortingNetworkStrategy<>(),
                new MergeSortStrategy<>(),
                new QuickSortStrategy<>(),
                new HeapSortStrategy<>(),
                new IntroSortStrategy<>(),
                new JdkSortStrategy<>());
    }

    @Test
    void medianOfThreeKillerForcesPlainQuicksortQuadratic() {
        for (int n : new int[] {1023, 2047}) {
            Integer[] killer = medianOfThreeKiller(n);
            long[] count = {0};
            plainMedianOfThreeQuick(killer.clone(), 0, n - 1, Comparator.naturalOrder(), count);
            assertTrue(count[0] > (long) n * n / 5,
                    "the killer should drive an unguarded median-of-three quicksort quadratic: n=" + n
                            + " comparisons=" + count[0] + " (n²/5=" + ((long) n * n / 5) + ")");
        }
    }

    @Test
    void introsortTamesTheKillerWithItsHeapFallback() {
        for (int n : new int[] {1023, 2047}) {
            Integer[] killer = medianOfThreeKiller(n);

            // plain (unguarded) median-of-three quick on this exact array -> quadratic
            long[] plain = {0};
            plainMedianOfThreeQuick(killer.clone(), 0, n - 1, Comparator.naturalOrder(), plain);

            // the engine's introsort on the same array -> correct, and sub-quadratic (the guard fires)
            SortBuffer<Integer> b = SortBuffer.of(Arrays.asList(killer.clone()), Comparator.<Integer>naturalOrder());
            new IntroSortStrategy<Integer>().sort(b, SortContext.noop());

            assertEquals(reference(killer), b.toList(), "introsort must still sort the killer correctly, n=" + n);

            long introCmp = b.comparisons();
            long bound = (long) (8.0 * n * (Math.log(n) / Math.log(2)));
            assertTrue(introCmp <= bound,
                    "introsort must stay sub-quadratic via its heap fallback: n=" + n + " comparisons="
                            + introCmp + " (bound 8·n·log₂n=" + bound + ")");
            assertTrue(introCmp * 4 < plain[0],
                    "the depth guard should save >4x comparisons vs unguarded quick: intro=" + introCmp
                            + " plain=" + plain[0] + " (n=" + n + ")");
        }
    }

    @Test
    void everyStrategyStillSortsTheKiller() {
        int n = 255; // within the proven size range for all strategies (cf. SortStrategyPropertyTest)
        Integer[] killer = medianOfThreeKiller(n);
        List<Integer> expected = reference(killer);
        for (SortStrategy<Integer> strategy : comparisonStrategies()) {
            SortBuffer<Integer> b = SortBuffer.of(Arrays.asList(killer.clone()), Comparator.<Integer>naturalOrder());
            strategy.sort(b, SortContext.noop());
            assertEquals(expected, b.toList(), "strategy " + strategy.id() + " on the killer");
        }
    }

    // ---- Bentley–McIlroy median-of-three killer adversary ----

    /**
     * Build an input that makes a median-of-three quicksort (the engine's pivot rule) degenerate. The
     * adversary keeps values "gas" (undetermined, comparing largest) until forced to freeze one; it
     * freezes whichever of two gas elements is <em>not</em> the running pivot candidate, so the pivot
     * stays extreme and partitions stay maximally unbalanced. Training runs a plain median-of-three
     * quicksort over identity tokens; the assigned values (in original index order) are the killer.
     */
    private static Integer[] medianOfThreeKiller(int n) {
        final int gas = n;                       // compares larger than every solid value 0..n-1
        final int[] val = new int[n];
        Arrays.fill(val, gas);
        final int[] nsolid = {0};
        final int[] candidate = {0};
        Comparator<Integer> adversary = (x, y) -> {
            if (val[x] == gas && val[y] == gas) {
                if (x == candidate[0]) {
                    val[x] = nsolid[0]++;
                } else {
                    val[y] = nsolid[0]++;
                }
            }
            if (val[x] == gas) {
                candidate[0] = x;
                return 1;
            }
            if (val[y] == gas) {
                candidate[0] = y;
                return -1;
            }
            return Integer.compare(val[x], val[y]);
        };
        Integer[] tokens = new Integer[n];
        for (int i = 0; i < n; i++) {
            tokens[i] = i;
        }
        plainMedianOfThreeQuick(tokens, 0, n - 1, adversary, new long[1]); // train (comparison count ignored)
        for (int i = 0; i < n; i++) {
            if (val[i] == gas) {
                val[i] = nsolid[0]++;            // distinct large values for any never-frozen tokens
            }
        }
        Integer[] killer = new Integer[n];
        for (int i = 0; i < n; i++) {
            killer[i] = val[i];                  // values in original index order
        }
        return killer;
    }

    /** Plain three-way median-of-three quicksort (no depth guard); counts every comparison into {@code count}. */
    private static void plainMedianOfThreeQuick(Integer[] a, int lo, int hi, Comparator<Integer> cmp, long[] count) {
        while (lo < hi) {
            int mid = lo + ((hi - lo) >>> 1);
            int p = medianOfThree(a, lo, mid, hi, cmp, count);
            swap(a, lo, p);
            Integer pivot = a[lo];
            int lt = lo, gt = hi, i = lo + 1;
            while (i <= gt) {
                int c = compare(cmp, a[i], pivot, count);
                if (c < 0) {
                    swap(a, lt++, i++);
                } else if (c > 0) {
                    swap(a, i, gt--);
                } else {
                    i++;
                }
            }
            if (lt - lo < hi - gt) {             // recurse smaller side, loop larger (bounds stack depth)
                plainMedianOfThreeQuick(a, lo, lt - 1, cmp, count);
                lo = gt + 1;
            } else {
                plainMedianOfThreeQuick(a, gt + 1, hi, cmp, count);
                hi = lt - 1;
            }
        }
    }

    private static int medianOfThree(Integer[] a, int x, int y, int z, Comparator<Integer> cmp, long[] count) {
        if (compare(cmp, a[x], a[y], count) < 0) {
            if (compare(cmp, a[y], a[z], count) < 0) {
                return y;
            }
            return compare(cmp, a[x], a[z], count) < 0 ? z : x;
        } else {
            if (compare(cmp, a[z], a[y], count) < 0) {
                return y;
            }
            return compare(cmp, a[z], a[x], count) < 0 ? z : x;
        }
    }

    private static int compare(Comparator<Integer> cmp, Integer x, Integer y, long[] count) {
        count[0]++;
        return cmp.compare(x, y);
    }

    private static void swap(Integer[] a, int i, int j) {
        Integer t = a[i];
        a[i] = a[j];
        a[j] = t;
    }

    private static List<Integer> reference(Integer[] a) {
        List<Integer> out = new ArrayList<>(Arrays.asList(a));
        out.sort(Comparator.naturalOrder());
        return out;
    }
}
