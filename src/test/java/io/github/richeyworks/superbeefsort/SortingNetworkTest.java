package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.strategy.SortingNetworkStrategy;
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
 * The branchless small-sort kernel must equal the reference sort: the comparator networks for
 * {@code n = 0..16} and the insertion fallback for larger inputs. (The networks themselves were also
 * verified offline via the 0/1 principle; this nails down the Java wiring.)
 */
class SortingNetworkTest {

    private static List<Integer> netSort(List<Integer> in) {
        SortBuffer<Integer> b = SortBuffer.of(in, Comparator.<Integer>naturalOrder());
        new SortingNetworkStrategy<Integer>().sort(b, SortContext.noop());
        return b.toList();
    }

    private static List<Integer> reference(List<Integer> in) {
        List<Integer> c = new ArrayList<>(in);
        c.sort(Comparator.naturalOrder());
        return c;
    }

    @Test
    void sortsEverySizeFromZeroToMaxAcrossShapes() {
        Random rnd = new Random(42);
        for (int n = 0; n <= 16; n++) { // 16 == SortingNetwork.MAX: every networked size
            List<Integer> random = new ArrayList<>();
            List<Integer> reversed = new ArrayList<>();
            List<Integer> fewDistinct = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                random.add(rnd.nextInt(50));
                reversed.add(n - i);
                fewDistinct.add(i % 3); // many ties - exercises equal-key comparators
            }
            for (List<Integer> in : List.of(random, reversed, fewDistinct)) {
                assertEquals(reference(in), netSort(in), "n=" + n + " input=" + in);
            }
        }
    }

    @Property(tries = 500)
    void matchesReferenceIncludingOversizeFallback(@ForAll("smallLists") List<Integer> data) {
        assertEquals(reference(data), netSort(data));
    }

    @Provide
    Arbitrary<List<Integer>> smallLists() {
        // up to 24 spans both the network path (n <= 16) and the insertion fallback (n > 16)
        return Arbitraries.integers().between(-30, 30).list().ofMaxSize(24);
    }
}
