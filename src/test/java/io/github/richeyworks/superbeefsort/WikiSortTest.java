package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.strategy.WikiSortStrategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stable, O(1)-aux {@link WikiSortStrategy} ({@code merge.wiki}): it must match the JDK reference
 * across random and pathological shapes, preserve input order for equal keys (stability), advertise
 * stable + in-place, and — unlike the O(n&nbsp;log&sup2;&nbsp;n) {@code merge.inplace} precursor — keep
 * its comparison count within O(n&nbsp;log&nbsp;n) on large random input.
 */
class WikiSortTest {

    private static List<Integer> sort(List<Integer> in) {
        SortBuffer<Integer> b = SortBuffer.of(in, Comparator.<Integer>naturalOrder());
        new WikiSortStrategy<Integer>().sort(b, SortContext.noop());
        return b.toList();
    }

    private static List<Integer> reference(List<Integer> in) {
        List<Integer> e = new ArrayList<>(in);
        e.sort(Comparator.naturalOrder());
        return e;
    }

    @Test
    void randomBatteryMatchesReference() {
        for (int seed = 0; seed < 40; seed++) {
            Random r = new Random(seed);
            int n = r.nextInt(600);
            int range = 1 + r.nextInt(50); // narrow -> duplicate-heavy (exercises the rotation fallback)
            List<Integer> in = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                in.add(r.nextInt(range));
            }
            assertEquals(reference(in), sort(in), "seed " + seed + " n=" + n + " range=" + range);
        }
    }

    @Test
    void distinctBatteryMatchesReference() {
        // distinct permutations drive the block-merge fast path (no fallback)
        for (int seed = 0; seed < 40; seed++) {
            int n = new Random(seed).nextInt(800);
            List<Integer> in = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                in.add(i);
            }
            Collections.shuffle(in, new Random(seed * 31L + 1));
            assertEquals(reference(in), sort(in), "distinct seed " + seed + " n=" + n);
        }
    }

    @Test
    void pathologicalShapesMatchReference() {
        for (int n : new int[]{0, 1, 2, 3, 16, 17, 64, 257, 500}) {
            assertEquals(reference(sorted(n)), sort(sorted(n)), "sorted n=" + n);
            assertEquals(reference(reversed(n)), sort(reversed(n)), "reversed n=" + n);
            assertEquals(reference(allEqual(n)), sort(allEqual(n)), "all-equal n=" + n);
            assertEquals(reference(sawtooth(n)), sort(sawtooth(n)), "sawtooth n=" + n);
            assertEquals(reference(organPipe(n)), sort(organPipe(n)), "organ-pipe n=" + n);
            assertEquals(reference(fewDistinct(n)), sort(fewDistinct(n)), "few-distinct n=" + n);
        }
    }

    private record Item(int key, int seq) {
    }

    @Test
    void stableForEqualKeys() {
        Random r = new Random(9);
        List<Item> in = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            in.add(new Item(r.nextInt(25), i)); // small key space -> many ties to keep ordered
        }
        Comparator<Item> byKey = Comparator.comparingInt(Item::key);
        SortBuffer<Item> b = SortBuffer.of(in, byKey);
        new WikiSortStrategy<Item>().sort(b, SortContext.noop());
        List<Item> out = b.toList();

        List<Item> expected = new ArrayList<>(in);
        expected.sort(byKey); // List.sort is stable
        assertEquals(expected, out);
        for (int i = 1; i < out.size(); i++) {
            if (out.get(i - 1).key() == out.get(i).key()) {
                assertTrue(out.get(i - 1).seq() < out.get(i).seq(), "equal keys keep input order");
            }
        }
    }

    @Test
    void stableForDenseDuplicatesAtScale() {
        // Large input with a small key space: the block-merge roll runs over many levels with duplicate
        // block heads, so this exercises the unique-value tag buffer + origin-stable selection at scale
        // (the path that previously degraded to the rotation merge whenever a duplicate appeared).
        Random r = new Random(123);
        List<Item> in = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            in.add(new Item(r.nextInt(40), i));
        }
        Comparator<Item> byKey = Comparator.comparingInt(Item::key);
        SortBuffer<Item> b = SortBuffer.of(in, byKey);
        new WikiSortStrategy<Item>().sort(b, SortContext.noop());
        List<Item> out = b.toList();

        List<Item> expected = new ArrayList<>(in);
        expected.sort(byKey);
        assertEquals(expected, out);
        for (int i = 1; i < out.size(); i++) {
            if (out.get(i - 1).key() == out.get(i).key()) {
                assertTrue(out.get(i - 1).seq() < out.get(i).seq(), "equal keys keep input order");
            }
        }
    }

    @Test
    void stableForNearDistinctWithSparseDuplicates() {
        // The case the duplicate-tolerant path most cares about: a mostly-distinct large array with a
        // handful of duplicate keys. A single duplicate used to pull the whole top-level merge onto the
        // O(n log^2 n) rotation path; now it stays on the block-merge roll and remains stable.
        Random r = new Random(77);
        List<Item> in = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            in.add(new Item(i, i)); // distinct keys
        }
        for (int k = 0; k < 60; k++) {
            int i = r.nextInt(in.size());
            in.set(i, new Item(r.nextInt(in.size()), in.get(i).seq())); // inject sparse collisions
        }
        Collections.shuffle(in, new Random(5));
        // re-stamp seq to reflect the (post-shuffle) input order we want stability against
        List<Item> stamped = new ArrayList<>(in.size());
        for (int i = 0; i < in.size(); i++) {
            stamped.add(new Item(in.get(i).key(), i));
        }
        Comparator<Item> byKey = Comparator.comparingInt(Item::key);
        SortBuffer<Item> b = SortBuffer.of(stamped, byKey);
        new WikiSortStrategy<Item>().sort(b, SortContext.noop());
        List<Item> out = b.toList();

        List<Item> expected = new ArrayList<>(stamped);
        expected.sort(byKey);
        assertEquals(expected, out);
        for (int i = 1; i < out.size(); i++) {
            if (out.get(i - 1).key() == out.get(i).key()) {
                assertTrue(out.get(i - 1).seq() < out.get(i).seq(), "equal keys keep input order");
            }
        }
    }

    @Test
    void advertisesStableInPlace() {
        var caps = new WikiSortStrategy<Integer>().capabilities();
        assertTrue(caps.stable(), "stable");
        assertTrue(caps.inPlace(), "in place (O(1) aux)");
        assertTrue(caps.comparisonBased(), "comparison based");
    }

    @Test
    void comparisonCountIsLinearithmic() {
        // For a large random permutation the block-merge path engages; comparisons must stay within
        // O(n log n) — well under the ~n*(log n)^2 an O(n log^2 n) merge would incur. C is generous to
        // stay robust across inputs while still ruling out the quadratic-log regime.
        int n = 10_000;
        List<Integer> in = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            in.add(i);
        }
        Collections.shuffle(in, new Random(42));

        SortBuffer<Integer> b = SortBuffer.of(in, Comparator.<Integer>naturalOrder());
        new WikiSortStrategy<Integer>().sort(b, SortContext.noop());

        assertEquals(reference(in), b.toList(), "must sort correctly");
        double log2n = Math.log(n) / Math.log(2);
        long bound = (long) (8.0 * n * log2n);
        assertTrue(b.comparisons() <= bound,
                "comparisons " + b.comparisons() + " should be <= " + bound + " (8 n log2 n)");
    }

    // ---- shape generators (mirror DifferentialTest / InPlaceMergeSortTest) ----

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

    private static List<Integer> sawtooth(int n) {
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            a.add(i % 8);
        }
        return a;
    }

    private static List<Integer> organPipe(int n) {
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            a.add(Math.min(i, n - 1 - i));
        }
        return a;
    }

    private static List<Integer> fewDistinct(int n) {
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            a.add((i * 31 + 7) % 4);
        }
        return a;
    }
}
