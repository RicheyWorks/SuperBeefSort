package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.StrategyCapabilities;
import io.github.richeyworks.superbeefsort.core.StrategyId;
import io.github.richeyworks.superbeefsort.registry.StrategyRegistry;
import io.github.richeyworks.superbeefsort.strategy.ParallelRadixSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.RadixSortStrategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correctness, stability, and parallel-path coverage for {@link ParallelRadixSortStrategy}
 * ({@code radix.lsd.parallel}). The forced-{@code parallelism} constructor drives the real multi-chunk
 * histogram/scatter even on small inputs, so the parallel code — not just its {@code p=1} fast path — is
 * exercised; the auto path is checked at a size past {@link ParallelRadixSortStrategy#PARALLEL_THRESHOLD}.
 * The decisive guarantee is that the parallel output equals both the JDK reference and the sequential
 * {@code radix.lsd} for every chunk count, on every shape — so multicore never changes the result.
 */
class ParallelRadixSortTest {

    // ---- helpers (each defensively copies, so a shared input list is never mutated) ----

    private static List<Integer> par(List<Integer> in, int parallelism) {
        SortBuffer<Integer> b =
                SortBuffer.of(new ArrayList<>(in), Comparator.<Integer>naturalOrder(), KeyEncoder.ofInt(i -> i));
        new ParallelRadixSortStrategy<Integer>(parallelism).sort(b, SortContext.noop());
        return b.toList();
    }

    private static List<Integer> seq(List<Integer> in) {
        SortBuffer<Integer> b =
                SortBuffer.of(new ArrayList<>(in), Comparator.<Integer>naturalOrder(), KeyEncoder.ofInt(i -> i));
        new RadixSortStrategy<Integer>().sort(b, SortContext.noop());
        return b.toList();
    }

    private static List<Integer> reference(List<Integer> in) {
        List<Integer> e = new ArrayList<>(in);
        e.sort(Comparator.naturalOrder());
        return e;
    }

    // ---- the core guarantee: parallel == reference == sequential, every shape, every chunk count ----

    @Test
    void matchesReferenceAndSequentialAcrossShapesAndChunkCounts() {
        int[] sizes = {0, 1, 2, 3, 16, 17, 64, 257, 500, 1000, 5000};
        int[] chunkCounts = {1, 2, 3, 4, 7, 8};
        for (int n : sizes) {
            List<List<Integer>> shapes = List.of(
                    sorted(n), reversed(n), allEqual(n), sawtooth(n, 8),
                    organPipe(n), fewDistinct(n, 4), random(n, 12345 + n), narrowBand(n, 777 + n));
            for (List<Integer> in : shapes) {
                List<Integer> ref = reference(in);
                assertEquals(ref, seq(in), "sequential radix.lsd sanity at n=" + n);
                for (int p : chunkCounts) {
                    assertEquals(ref, par(in, p),
                            "radix.lsd.parallel(p=" + p + ") disagreed with reference at n=" + n);
                }
            }
        }
    }

    @Test
    void largeRandomUsesAutoParallelPathAndMatchesReference() {
        Random r = new Random(7);
        List<Integer> in = new ArrayList<>();
        for (int i = 0; i < 100_000; i++) { // > PARALLEL_THRESHOLD -> derived chunk count
            in.add(r.nextInt());
        }
        SortBuffer<Integer> b =
                SortBuffer.of(in, Comparator.<Integer>naturalOrder(), KeyEncoder.ofInt(i -> i));
        new ParallelRadixSortStrategy<Integer>().sort(b, SortContext.noop()); // auto
        assertEquals(reference(in), b.toList());
    }

    // ---- stability ----

    private record Item(int key, int seq) {
    }

    /** @param parallelism 0 forces the auto path; a positive value forces that many chunks. */
    private static void assertStable(List<Item> in, int parallelism) {
        Comparator<Item> byKey = Comparator.comparingInt(Item::key);
        SortBuffer<Item> b = SortBuffer.of(new ArrayList<>(in), byKey, KeyEncoder.ofInt(Item::key));
        new ParallelRadixSortStrategy<Item>(parallelism).sort(b, SortContext.noop());
        List<Item> out = b.toList();

        List<Item> expected = new ArrayList<>(in);
        expected.sort(byKey); // List.sort is stable
        assertEquals(expected, out);
        for (int i = 1; i < out.size(); i++) {
            if (out.get(i - 1).key() == out.get(i).key()) {
                assertTrue(out.get(i - 1).seq() < out.get(i).seq(), "equal keys must keep input order");
            }
        }
    }

    @Test
    void stableForEqualKeysForcedParallel() {
        Random r = new Random(5);
        List<Item> in = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            in.add(new Item(r.nextInt(20), i)); // tiny key space -> many ties across chunk boundaries
        }
        assertStable(in, 4);
    }

    @Test
    void stableAtScaleAutoParallel() {
        Random r = new Random(6);
        List<Item> in = new ArrayList<>();
        for (int i = 0; i < 80_000; i++) { // > PARALLEL_THRESHOLD, with heavy ties
            in.add(new Item(r.nextInt(256), i));
        }
        assertStable(in, 0); // auto
    }

    // ---- sign-flip extremes (the capped 8-bit multi-pass path when parallel) ----

    @Test
    void integerExtremesForcedParallel() {
        List<Integer> ext = new ArrayList<>(Arrays.asList(
                Integer.MIN_VALUE, Integer.MAX_VALUE, 0, -1, 1, Integer.MIN_VALUE, 2, -2));
        assertEquals(reference(ext), par(ext, 3));
    }

    @Test
    void longExtremesForcedParallel() {
        // Full 64-bit range with p>1 => bits capped to 8 => an 8-pass sort; exercises the cap path.
        List<Long> in = new ArrayList<>(List.of(
                Long.MIN_VALUE, Long.MAX_VALUE, -1L, 0L, 1L,
                Long.MIN_VALUE, Long.MAX_VALUE,            // duplicates of the extremes
                Long.MIN_VALUE + 1L, Long.MAX_VALUE - 1L));
        SortBuffer<Long> b = SortBuffer.of(in, Comparator.<Long>naturalOrder(), KeyEncoder.ofLong(x -> x));
        new ParallelRadixSortStrategy<Long>(4).sort(b, SortContext.noop());
        List<Long> expected = new ArrayList<>(in);
        expected.sort(Comparator.naturalOrder());
        assertEquals(expected, b.toList());
    }

    @Test
    void longKeysWholeRangeForcedParallel() {
        Random r = new Random(8);
        List<Long> in = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            in.add(r.nextLong());
        }
        SortBuffer<Long> b = SortBuffer.of(in, Comparator.<Long>naturalOrder(), KeyEncoder.ofLong(x -> x));
        new ParallelRadixSortStrategy<Long>(4).sort(b, SortContext.noop());
        List<Long> expected = new ArrayList<>(in);
        expected.sort(Comparator.naturalOrder());
        assertEquals(expected, b.toList());
    }

    // ---- capabilities, id, registration, construction ----

    @Test
    void capabilitiesAndId() {
        ParallelRadixSortStrategy<Integer> s = new ParallelRadixSortStrategy<>();
        StrategyCapabilities caps = s.capabilities();
        assertTrue(caps.stable(), "radix is stable");
        assertFalse(caps.inPlace(), "radix is out of place");
        assertFalse(caps.comparisonBased(), "radix is non-comparison");
        assertTrue(caps.parallel(), "declares itself parallel");
        assertTrue(caps.requiresIntegerKeys(), "needs a KeyEncoder");
        assertEquals(StrategyCapabilities.AuxMemory.LINEAR, caps.auxMemory());
        assertEquals(StrategyId.of("radix.lsd.parallel"), s.id());
        assertEquals(ParallelRadixSortStrategy.ID, s.id());
    }

    @Test
    void registeredInDefaultRegistry() {
        StrategyRegistry registry = StrategyRegistry.withDefaults();
        assertTrue(registry.contains(ParallelRadixSortStrategy.ID), "radix.lsd.parallel must be registered");
        assertEquals(ParallelRadixSortStrategy.ID, registry.get(ParallelRadixSortStrategy.ID).id());
    }

    @Test
    void negativeParallelismRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ParallelRadixSortStrategy<>(-1));
    }

    // ---- shape generators (mirror DifferentialTest) ----

    private static List<Integer> sorted(int n) {
        List<Integer> a = new ArrayList<>(Math.max(0, n));
        for (int i = 0; i < n; i++) {
            a.add(i);
        }
        return a;
    }

    private static List<Integer> reversed(int n) {
        List<Integer> a = new ArrayList<>(Math.max(0, n));
        for (int i = 0; i < n; i++) {
            a.add(n - i);
        }
        return a;
    }

    private static List<Integer> allEqual(int n) {
        List<Integer> a = new ArrayList<>(Math.max(0, n));
        for (int i = 0; i < n; i++) {
            a.add(7);
        }
        return a;
    }

    private static List<Integer> sawtooth(int n, int period) {
        List<Integer> a = new ArrayList<>(Math.max(0, n));
        for (int i = 0; i < n; i++) {
            a.add(i % period);
        }
        return a;
    }

    private static List<Integer> organPipe(int n) {
        List<Integer> a = new ArrayList<>(Math.max(0, n));
        for (int i = 0; i < n; i++) {
            a.add(Math.min(i, n - 1 - i));
        }
        return a;
    }

    private static List<Integer> fewDistinct(int n, int distinct) {
        List<Integer> a = new ArrayList<>(Math.max(0, n));
        for (int i = 0; i < n; i++) {
            a.add((i * 31 + 7) % distinct);
        }
        return a;
    }

    private static List<Integer> random(int n, long seed) {
        Random rng = new Random(seed);
        List<Integer> a = new ArrayList<>(Math.max(0, n));
        for (int i = 0; i < n; i++) {
            a.add(rng.nextInt(Math.max(1, n)) - n / 2);
        }
        return a;
    }

    /** Narrow band of high-magnitude values: the entropy plan's offset-by-min + (parallel) 8-bit cap path. */
    private static List<Integer> narrowBand(int n, long seed) {
        Random rng = new Random(seed);
        List<Integer> a = new ArrayList<>(Math.max(0, n));
        for (int i = 0; i < n; i++) {
            a.add(1_000_000 + rng.nextInt(1000));
        }
        return a;
    }
}
