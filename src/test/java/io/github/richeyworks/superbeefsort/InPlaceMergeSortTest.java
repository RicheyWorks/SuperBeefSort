package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.strategy.InPlaceMergeSortStrategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stable, O(1)-aux {@link InPlaceMergeSortStrategy}: it must match the JDK reference across random and
 * pathological shapes, preserve input order for equal keys (stability), and advertise stable + in-place.
 */
class InPlaceMergeSortTest {

    private static List<Integer> sort(List<Integer> in) {
        SortBuffer<Integer> b = SortBuffer.of(in, Comparator.<Integer>naturalOrder());
        new InPlaceMergeSortStrategy<Integer>().sort(b, SortContext.noop());
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
            int range = 1 + r.nextInt(50); // narrow -> duplicate-heavy
            List<Integer> in = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                in.add(r.nextInt(range));
            }
            assertEquals(reference(in), sort(in), "seed " + seed + " n=" + n + " range=" + range);
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
        new InPlaceMergeSortStrategy<Item>().sort(b, SortContext.noop());
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
    void advertisesStableInPlace() {
        var caps = new InPlaceMergeSortStrategy<Integer>().capabilities();
        assertTrue(caps.stable(), "stable");
        assertTrue(caps.inPlace(), "in place (O(1) aux)");
    }

    // ---- shape generators (mirror DifferentialTest) ----

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
