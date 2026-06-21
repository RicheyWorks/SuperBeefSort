package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.superbeefsort.external.ExternalSortResult;
import io.github.richeyworks.superbeefsort.external.SpillSerializer;
import io.github.richeyworks.superbeefsort.select.SelectionPolicy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * External merge sort: correctness across pathological shapes, multi-pass merges, and key types.
 * Every test is a differential: external sort output == JDK-reference sort on the same input.
 */
class ExternalMergeSortTest {

    // ---- helpers ----

    private static List<Integer> reference(List<Integer> in) {
        List<Integer> copy = new ArrayList<>(in);
        copy.sort(Comparator.naturalOrder());
        return copy;
    }

    private static List<Integer> externalSort(List<Integer> input, int runSize) throws IOException {
        return BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(input)
                .external(SpillSerializer.forIntegers())
                .runSize(runSize)
                .toList();
    }

    private static List<Integer> random(int n, int range, long seed) {
        Random r = new Random(seed);
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) a.add(r.nextInt(range));
        return a;
    }

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
        return Collections.nCopies(n, 42);
    }

    private static List<Integer> sawtooth(int n) {
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) a.add(i % 8);
        return a;
    }

    private static List<Integer> fewDistinct(int n) {
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) a.add((i * 31 + 7) % 5);
        return a;
    }

    // ---- tests ----

    @Test
    void emptyInputProducesEmptyList() throws IOException {
        assertEquals(List.of(), externalSort(List.of(), 100));
    }

    @Test
    void singleElementPassesThrough() throws IOException {
        assertEquals(List.of(7), externalSort(List.of(7), 100));
    }

    @Test
    void singleRunMatchesReference() throws IOException {
        // runSize larger than input → one run, no merge pass
        for (int n : new int[]{2, 3, 17, 100, 500}) {
            List<Integer> in = random(n, 100, n);
            assertEquals(reference(in), externalSort(in, 10_000), "n=" + n);
        }
    }

    @Test
    void multipleRunsSinglePassMatchesReference() throws IOException {
        // 200 elements, runSize=20 → 10 runs; fanIn=16 → one merge pass (10 ≤ 16)
        List<Integer> in = random(200, 100, 42L);
        List<Integer> result = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(in)
                .external(SpillSerializer.forIntegers())
                .runSize(20)
                .fanIn(16)
                .toList();
        assertEquals(reference(in), result);
    }

    @Test
    void multiplePassesMatchReference() throws IOException {
        // 100 elements, runSize=5 → 20 runs; fanIn=4 →
        //   pass 1: 20 runs → 5 intermediates (5 groups of 4)
        //   pass 2: 5 intermediates → 2 intermediates (4+1)
        //   final : 2 → merged
        List<Integer> in = random(100, 50, 7L);
        List<Integer> result = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(in)
                .external(SpillSerializer.forIntegers())
                .runSize(5)
                .fanIn(4)
                .toList();
        assertEquals(reference(in), result);
    }

    @Test
    void pathologicalShapesMatchReference() throws IOException {
        int runSize = 10;
        for (int n : new int[]{0, 1, 7, 11, 50, 101}) {
            assertEquals(reference(sorted(n)),    externalSort(sorted(n),    runSize), "sorted n="   + n);
            assertEquals(reference(reversed(n)),  externalSort(reversed(n),  runSize), "reversed n=" + n);
            assertEquals(reference(allEqual(n)),  externalSort(allEqual(n),  runSize), "all-equal n="  + n);
            assertEquals(reference(sawtooth(n)),  externalSort(sawtooth(n),  runSize), "sawtooth n=" + n);
            assertEquals(reference(fewDistinct(n)), externalSort(fewDistinct(n), runSize), "few-distinct n=" + n);
        }
    }

    @Test
    void randomBatteryMatchesReference() throws IOException {
        for (int seed = 0; seed < 20; seed++) {
            Random r = new Random(seed);
            int n = r.nextInt(300);
            int range = 1 + r.nextInt(60);
            int runSize = 1 + r.nextInt(30);
            List<Integer> in = random(n, range, seed);
            assertEquals(reference(in), externalSort(in, runSize),
                    "seed=" + seed + " n=" + n + " range=" + range + " runSize=" + runSize);
        }
    }

    @Test
    void longSerializerRoundTrips() throws IOException {
        List<Long> in = new ArrayList<>(List.of(5L, 2L, 9L, 1L, 7L, -3L, Long.MIN_VALUE, Long.MAX_VALUE));
        List<Long> expected = new ArrayList<>(in);
        expected.sort(Comparator.naturalOrder());
        List<Long> result = BeefSort.with(Comparator.<Long>naturalOrder())
                .source(in)
                .external(SpillSerializer.forLongs())
                .runSize(3)
                .toList();
        assertEquals(expected, result);
    }

    @Test
    void stringSerializerRoundTrips() throws IOException {
        List<String> in = List.of("banana", "apple", "cherry", "date", "elderberry");
        List<String> expected = new ArrayList<>(in);
        expected.sort(Comparator.naturalOrder());
        List<String> result = BeefSort.with(Comparator.<String>naturalOrder())
                .source(in)
                .external(SpillSerializer.forStrings())
                .runSize(2)
                .toList();
        assertEquals(expected, result);
    }

    @Test
    void externalSortResultHasCorrectMetrics() throws IOException {
        // 50 elements, runSize=10 → 5 runs, fanIn=16 → 1 merge pass
        List<Integer> in = random(50, 100, 1L);
        ExternalSortResult res = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(in)
                .external(SpillSerializer.forIntegers())
                .runSize(10)
                .fanIn(16)
                .feedInto(OrderedSet.withNaturalOrder(new RedBlackStrategy<Integer>()));
        assertEquals(50, res.elements());
        assertEquals(5, res.runs());
        assertEquals(1, res.mergePasses());
        assertTrue(res.elapsedNanos() > 0);
        assertTrue(res.elapsedMillis() >= 0.0);
    }

    @Test
    void feedIntoOrderedSetContainsAllDistinctElements() throws IOException {
        List<Integer> in = random(200, 100, 99L);
        List<Integer> distinctSorted = reference(in).stream().distinct().toList();
        OrderedSet<Integer> set = OrderedSet.withNaturalOrder(new RedBlackStrategy<Integer>());
        BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(in)
                .external(SpillSerializer.forIntegers())
                .runSize(30)
                .feedInto(set);
        assertEquals(distinctSorted.size(), set.size(), "distinct count");
        // Spot-check: min and max are correct
        assertEquals(distinctSorted.get(0), set.minimum());
        assertEquals(distinctSorted.get(distinctSorted.size() - 1), set.maximum());
    }

    @Test
    void stableUnderStablePolicy() throws IOException {
        // Record (key, seqIndex); sort by key only and verify equal keys keep input order.
        record Item(int key, int seq) {}
        Random r = new Random(42);
        List<Item> in = new ArrayList<>();
        for (int i = 0; i < 300; i++) in.add(new Item(r.nextInt(10), i)); // small key space → many ties

        Comparator<Item> byKey = Comparator.comparingInt(Item::key);
        List<Item> result = BeefSort.with(byKey)
                .source(in)
                .policy(SelectionPolicy.STABLE) // each chunk sort is stable → overall stable
                .external(new SpillSerializer<Item>() {
                    @Override
                    public void write(Item v, java.io.DataOutputStream out) throws IOException {
                        out.writeInt(v.key());
                        out.writeInt(v.seq());
                    }
                    @Override
                    public Item read(java.io.DataInputStream inp) throws IOException {
                        return new Item(inp.readInt(), inp.readInt());
                    }
                })
                .runSize(30) // 10 runs
                .fanIn(4)    // 3 passes
                .toList();

        // Reference: JDK stable sort by key
        List<Item> expected = new ArrayList<>(in);
        expected.sort(byKey);
        assertEquals(expected, result);

        // Verify: equal-key groups preserve original seq order
        for (int i = 1; i < result.size(); i++) {
            if (result.get(i - 1).key() == result.get(i).key()) {
                assertTrue(result.get(i - 1).seq() < result.get(i).seq(),
                        "equal keys must keep input order (stability)");
            }
        }
    }

    // ---- ADR done-metric: large-scale multi-pass ----

    @Test
    void largeScaleMultiPassMatchesReference() throws IOException {
        // n=5000, runSize=50 → 100 runs; fanIn=3 → 5 passes (100→34→12→4→2→done)
        List<Integer> in = random(5_000, 1_000, 99991L);
        List<Integer> result = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(in)
                .external(SpillSerializer.forIntegers())
                .runSize(50)
                .fanIn(3)
                .toList();
        assertEquals(reference(in), result);
    }

    @Test
    void largeScaleMultiPassDuplicateHeavyStability() throws IOException {
        // 3000 records with 8 distinct keys — many ties — multi-pass (30 runs, 3 passes with fanIn=4)
        record Tagged(int key, int idx) {}
        List<Tagged> in = new ArrayList<>(3_000);
        for (int i = 0; i < 3_000; i++) in.add(new Tagged(i % 8, i));

        Comparator<Tagged> byKey = Comparator.comparingInt(Tagged::key);
        List<Tagged> result = BeefSort.with(byKey)
                .source(in)
                .policy(SelectionPolicy.STABLE)
                .external(new SpillSerializer<Tagged>() {
                    @Override
                    public void write(Tagged v, java.io.DataOutputStream out) throws IOException {
                        out.writeInt(v.key());
                        out.writeInt(v.idx());
                    }
                    @Override
                    public Tagged read(java.io.DataInputStream inp) throws IOException {
                        return new Tagged(inp.readInt(), inp.readInt());
                    }
                })
                .runSize(100)   // 30 runs
                .fanIn(4)       // 30→8→2 → 3 passes
                .toList();

        List<Tagged> expected = new ArrayList<>(in);
        expected.sort(byKey);
        assertEquals(expected, result);

        for (int i = 1; i < result.size(); i++) {
            if (result.get(i - 1).key() == result.get(i).key()) {
                assertTrue(result.get(i - 1).idx() < result.get(i).idx(),
                        "stability violated at position " + i);
            }
        }
    }
}
