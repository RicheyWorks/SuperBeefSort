package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.strategy.RadixSortStrategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correctness of the entropy-aware {@link RadixSortStrategy} across range shapes that exercise the
 * offset-by-min + adaptive-base plan: a narrow band of large values (the case the old fixed schedule
 * handled poorly), the full signed range with negatives, longs, the extreme values, and stability.
 */
class AdaptiveRadixTest {

    private static List<Integer> radix(List<Integer> in) {
        SortBuffer<Integer> b = SortBuffer.of(in, Comparator.<Integer>naturalOrder(), KeyEncoder.ofInt(i -> i));
        new RadixSortStrategy<Integer>().sort(b, SortContext.noop());
        return b.toList();
    }

    private static List<Integer> reference(List<Integer> in) {
        List<Integer> e = new ArrayList<>(in);
        e.sort(Comparator.naturalOrder());
        return e;
    }

    @Test
    void narrowBandOfLargeValues() {
        Random r = new Random(1);
        List<Integer> in = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            in.add(1_000_000 + r.nextInt(1000)); // ~10 varying bits, high magnitude
        }
        assertEquals(reference(in), radix(in));
    }

    @Test
    void fullSignedRangeWithNegatives() {
        Random r = new Random(2);
        List<Integer> in = new ArrayList<>();
        for (int i = 0; i < 6000; i++) {
            in.add(r.nextInt()); // entire int range incl. negatives
        }
        assertEquals(reference(in), radix(in));
    }

    @Test
    void boundedRangeMatchesReference() {
        Random r = new Random(3);
        List<Integer> in = new ArrayList<>();
        for (int i = 0; i < 4000; i++) {
            in.add(r.nextInt(2001) - 1000); // [-1000,1000], like the property suite
        }
        assertEquals(reference(in), radix(in));
    }

    @Test
    void edgeCasesAndExtremes() {
        assertEquals(List.of(), radix(new ArrayList<>()));
        assertEquals(List.of(7), radix(new ArrayList<>(List.of(7))));
        assertEquals(reference(List.of(5, 5, 5, 5)), radix(new ArrayList<>(List.of(5, 5, 5, 5))));
        List<Integer> ext = new ArrayList<>(Arrays.asList(Integer.MIN_VALUE, Integer.MAX_VALUE, 0, -1, 1, Integer.MIN_VALUE));
        assertEquals(reference(ext), radix(ext));
    }

    @Test
    void longKeysAcrossTheWholeRange() {
        Random r = new Random(4);
        List<Long> in = new ArrayList<>();
        for (int i = 0; i < 4000; i++) {
            in.add(r.nextLong());
        }
        SortBuffer<Long> b = SortBuffer.of(in, Comparator.<Long>naturalOrder(), KeyEncoder.ofLong(x -> x));
        new RadixSortStrategy<Long>().sort(b, SortContext.noop());
        List<Long> expected = new ArrayList<>(in);
        expected.sort(Comparator.naturalOrder());
        assertEquals(expected, b.toList());
    }

    private record Item(int key, int seq) {
    }

    @Test
    void stableForEqualKeys() {
        Random r = new Random(5);
        List<Item> in = new ArrayList<>();
        for (int i = 0; i < 4000; i++) {
            in.add(new Item(r.nextInt(20), i)); // tiny key space -> many ties
        }
        Comparator<Item> byKey = Comparator.comparingInt(Item::key);
        SortBuffer<Item> b = SortBuffer.of(in, byKey, KeyEncoder.ofInt(Item::key));
        new RadixSortStrategy<Item>().sort(b, SortContext.noop());
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
}
