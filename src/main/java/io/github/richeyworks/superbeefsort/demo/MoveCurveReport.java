package io.github.richeyworks.superbeefsort.demo;

import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.strategy.InPlaceMergeSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.MergeSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.WikiSortStrategy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Prints the move / comparison growth curve for the three merge strategies on a shuffled distinct
 * permutation, each metric normalised to {@code n * log2(n)}. The shape of the <em>moves</em> column is
 * the point:
 *
 * <ul>
 *   <li>{@code merge} (O(n) scratch) — the O(n&nbsp;log&nbsp;n) ideal; its ratio is flat at ~1.0.</li>
 *   <li>{@code merge.inplace} — O(n&nbsp;log&sup2;&nbsp;n) moves; the ratio climbs roughly linearly with
 *       log&nbsp;n (≈4 → 9 from 1k → 1M).</li>
 *   <li>{@code merge.wiki} — O(n&nbsp;log&nbsp;n) moves with O(1) auxiliary memory; the ratio flattens
 *       and stays near {@code merge}'s ideal, overtaking {@code merge.inplace} past n≈10k and pulling
 *       further ahead as n grows. Its comparison ratio is higher — the block-merge trade.</li>
 * </ul>
 *
 * <p>A flat moves/(n&nbsp;log&nbsp;n) column means O(n&nbsp;log&nbsp;n); a column that rises with n means
 * O(n&nbsp;log&sup2;&nbsp;n). Run with {@code ./gradlew moveCurve}. This is a deterministic analytical
 * report (it counts the {@link SortBuffer}'s metered operations), not a wall-clock benchmark — see
 * {@code SortStrategyBenchmark} for timing.</p>
 */
public final class MoveCurveReport {

    private MoveCurveReport() {
    }

    /** Sort a fresh copy of {@code data}, assert it is ordered, and return {@code {comparisons, moves}}. */
    private static long[] run(SortStrategy<Integer> strategy, List<Integer> data) {
        SortBuffer<Integer> b = SortBuffer.of(data, Comparator.<Integer>naturalOrder());
        strategy.sort(b, SortContext.noop());
        for (int i = 1; i < b.size(); i++) {
            if (b.compare(i - 1, i) > 0) {
                throw new IllegalStateException(strategy.id() + " did not sort at n=" + data.size());
            }
        }
        return new long[]{b.comparisons(), b.moves()};
    }

    public static void main(String[] args) {
        int[] sizes = {1_000, 10_000, 100_000, 1_000_000};
        System.out.printf("%-10s | %-21s | %-21s | %-21s%n",
                "n", "merge (O(n) aux)", "merge.inplace", "merge.wiki");
        System.out.printf("%-10s | %-21s | %-21s | %-21s%n",
                "", "cmp/nlgn  mov/nlgn", "cmp/nlgn  mov/nlgn", "cmp/nlgn  mov/nlgn");
        System.out.println("-".repeat(84));
        for (int n : sizes) {
            List<Integer> data = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                data.add(i);
            }
            Collections.shuffle(data, new Random(7));
            double nlgn = (double) n * (Math.log(n) / Math.log(2));

            long[] m = run(new MergeSortStrategy<>(), data);
            long[] ip = run(new InPlaceMergeSortStrategy<>(), data);
            long[] wk = run(new WikiSortStrategy<>(), data);

            System.out.printf("%-10d | %8.2f %8.2f    | %8.2f %8.2f    | %8.2f %8.2f%n",
                    n, m[0] / nlgn, m[1] / nlgn, ip[0] / nlgn, ip[1] / nlgn, wk[0] / nlgn, wk[1] / nlgn);
        }
        System.out.println();
        System.out.println("Flat moves/(n log n) column => O(n log n); a column rising with n => O(n log^2 n).");
        System.out.println("merge.inplace's moves climb; merge.wiki stays near merge's flat ideal, with O(1) aux.");
    }
}
