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
    void organPipeAscendsThenDescends() {
        List<Integer> data = Workloads.organPipe(1_000);
        int peak = data.indexOf(data.stream().mapToInt(Integer::intValue).max().orElseThrow());
        for (int i = 1; i <= peak; i++) {
            assertTrue(data.get(i - 1) <= data.get(i), "ascending flank broken at " + i);
        }
        for (int i = peak + 1; i < data.size(); i++) {
            assertTrue(data.get(i - 1) >= data.get(i), "descending flank broken at " + i);
        }
    }

    @Test
    void zigzagHasNoRunLongerThanTwo() {
        List<Integer> data = Workloads.zigzag(1_000);
        int run = 1;
        for (int i = 1; i < data.size(); i++) {
            run = data.get(i - 1) <= data.get(i) ? run + 1 : 1;
            assertTrue(run <= 2, "an ascending run survived at " + i);
        }
    }

    @Test
    void allEqualIsExactlyThat() {
        assertEquals(1, new HashSet<>(Workloads.allEqual(500, 7)).size());
    }

    @Test
    void stringShapesHaveTheirFingerprints() {
        // paths: heavy shared prefixes — every entry starts with the same first segment shape.
        for (String p : Workloads.paths(200, 3, 4, 1L)) {
            assertTrue(p.startsWith("/seg"), "path shape broken: " + p);
            assertTrue(p.contains("/leaf"));
        }
        // paddedNumbers: fixed width, so byte order IS numeric order (radix's best case).
        List<String> nums = Workloads.paddedNumbers(500, 8, 2L);
        for (String s : nums) {
            assertEquals(8, s.length());
        }
        List<String> sorted = new java.util.ArrayList<>(nums);
        sorted.sort(String::compareTo);
        List<String> numeric = new java.util.ArrayList<>(nums);
        numeric.sort(java.util.Comparator.comparingLong(Long::parseLong));
        assertEquals(numeric, sorted, "byte order must equal numeric order for padded keys");
        // uuids: canonical 36-char layout; words: 3..12 lowercase.
        for (String u : Workloads.uuids(100, 3L)) {
            assertEquals(36, u.length());
        }
        for (String w : Workloads.words(500, 4L)) {
            assertTrue(w.length() >= 3 && w.length() <= 12 && w.chars().allMatch(Character::isLowerCase));
        }
    }

    @Test
    void zipfKeyStreamIsHeadHeavy() {
        IntSupplier keys = Workloads.zipfKeys(1_000, 1.1, 8L);
        Map<Integer, Integer> freq = new HashMap<>();
        for (int i = 0; i < 30_000; i++) {
            freq.merge(keys.getAsInt(), 1, Integer::sum);
        }
        assertTrue(freq.getOrDefault(0, 0) > 5 * Math.max(1, freq.getOrDefault(100, 0)),
                "rank 0 must dwarf rank 100 in the stream too");
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
