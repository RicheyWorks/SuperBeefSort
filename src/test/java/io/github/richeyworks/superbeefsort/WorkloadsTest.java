package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.superbeefsort.workload.Regime;
import io.github.richeyworks.superbeefsort.workload.Workloads;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The menagerie's fingerprints: each generator must actually have the statistical shape it
 * advertises (that shape is what steers the profiler → selector and the control plane), and all
 * of them must be deterministic under a fixed seed.
 */
class WorkloadsTest {

    @Test
    void generatorsAreDeterministic() {
        assertEquals(Workloads.uniform(1_000, 500, 9L), Workloads.uniform(1_000, 500, 9L));
        assertEquals(Workloads.zipf(1_000, 256, 1.1, 9L), Workloads.zipf(1_000, 256, 1.1, 9L));
    }

    @Test
    void nearlySortedIsMostlyAscending() {
        List<Integer> data = Workloads.nearlySorted(10_000, 0.02, 1L);
        int ascending = 0;
        for (int i = 1; i < data.size(); i++) {
            if (data.get(i - 1) <= data.get(i)) ascending++;
        }
        assertTrue(ascending > 0.9 * data.size(),
                "2% disorder must leave >90% of adjacent pairs ascending; was " + ascending);
    }

    @Test
    void reversedIsStrictlyDescending() {
        List<Integer> data = Workloads.reversed(1_000);
        for (int i = 1; i < data.size(); i++) {
            assertTrue(data.get(i - 1) > data.get(i));
        }
    }

    @Test
    void sawtoothHasTheDeclaredRunStructure() {
        List<Integer> data = Workloads.sawtooth(1_000, 10);
        int resets = 0;
        for (int i = 1; i < data.size(); i++) {
            if (data.get(i) < data.get(i - 1)) resets++;
        }
        assertEquals(9, resets, "10 teeth = 9 descents between runs");
    }

    @Test
    void duplicateHeavyStaysWithinItsDistinctBudget() {
        List<Integer> data = Workloads.duplicateHeavy(10_000, 32, 2L);
        assertTrue(new HashSet<>(data).size() <= 32);
    }

    @Test
    void timestampsInvertOnlyWithinTheJitterRadius() {
        int jitter = 8;
        List<Integer> data = Workloads.timestamps(10_000, jitter, 3L);
        for (int i = 1; i < data.size(); i++) {
            assertTrue(data.get(i - 1) - data.get(i) <= jitter,
                    "an inversion deeper than the jitter radius at " + i);
        }
    }

    @Test
    void zipfIsHeadHeavy() {
        List<Integer> data = Workloads.zipf(50_000, 1_000, 1.1, 4L);
        Map<Integer, Integer> freq = new HashMap<>();
        for (int k : data) {
            freq.merge(k, 1, Integer::sum);
        }
        int rank0 = freq.getOrDefault(0, 0);
        int rank100 = freq.getOrDefault(100, 0);
        assertTrue(rank0 > 5 * Math.max(1, rank100),
                "rank 0 must dwarf rank 100: " + rank0 + " vs " + rank100);
    }

    @Test
    void hotSetKeysConcentrate() {
        IntSupplier keys = Workloads.hotSetKeys(4, 0.9, 100_000, 5L);
        Map<Integer, Integer> freq = new HashMap<>();
        for (int i = 0; i < 20_000; i++) {
            freq.merge(keys.getAsInt(), 1, Integer::sum);
        }
        long inTopFour = freq.values().stream().sorted((a, b) -> b - a).limit(4)
                .mapToLong(Integer::longValue).sum();
        assertTrue(inTopFour > 0.8 * 20_000,
                "4 hot keys at 0.9 must take >80% of draws; took " + inTopFour);
    }

    @Test
    void climbingKeysStrictlyIncrease() {
        IntSupplier keys = Workloads.climbingKeys(3, 6L);
        int prev = Integer.MIN_VALUE;
        for (int i = 0; i < 1_000; i++) {
            int k = keys.getAsInt();
            assertTrue(k > prev);
            prev = k;
        }
    }

    @Test
    void regimeValidatesItsContract() {
        assertThrows(IllegalArgumentException.class,
                () -> Regime.of("bad", 0, 0.5, 0.5, () -> 1));
        assertThrows(IllegalArgumentException.class,
                () -> Regime.of("bad", 10, 1.5, 0.5, () -> 1));
        assertEquals(5, Workloads.aquariumPlaylist(1L).size(), "the default lap has five habitats");
        assertTrue(Workloads.aquariumPlaylist(1L).stream().anyMatch(r -> r.window() > 0),
                "the playlist must exercise the sliding window");
    }
}
