package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.profile.Distribution;
import io.github.richeyworks.superbeefsort.stream.AdaptiveStreamSorter;
import io.github.richeyworks.superbeefsort.stream.DriftDetector;
import io.github.richeyworks.superbeefsort.stream.DriftSignal;
import io.github.richeyworks.superbeefsort.stream.DriftVerdict;
import io.github.richeyworks.superbeefsort.stream.StreamSortResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concept-drift detection on streaming workloads (docs/architecture-csrbt-integration.md §2.3): the
 * {@link DriftSignal} fingerprint + {@link DriftDetector} decide when the data distribution has moved
 * enough to re-select the sort strategy, and {@link AdaptiveStreamSorter} drives "stable until the data
 * changes" — the sort-side mirror of CSRBT's morph controller. Three layers are covered: the distance
 * geometry, the sequential detector (baseline / stationary / drift / cooldown / warmup), and the
 * end-to-end driver against a real CSRBT {@code OrderedSet}.
 */
class ConceptDriftTest {

    // ---- signal geometry ---------------------------------------------------

    private static DriftSignal sig(double sortedness, double inversion, double cardinality,
                                   Distribution dist, boolean keyStats, double center, double span) {
        return new DriftSignal(1000, sortedness, inversion, cardinality, dist, keyStats, center, span);
    }

    @Test
    void distanceIsMaxOverFacetsAndZeroWhenIdentical() {
        DriftSignal base = sig(0.5, 0.5, 0.6, Distribution.UNIFORM, false, Double.NaN, Double.NaN);
        assertEquals(0.0, base.distanceTo(base), 1e-9);

        DriftSignal moreSorted = sig(0.8, 0.5, 0.6, Distribution.UNIFORM, false, Double.NaN, Double.NaN);
        assertEquals(0.3, moreSorted.distanceTo(base), 1e-9, "max facet delta = the sortedness move");
    }

    @Test
    void distributionClassChangeAndKeyStatsFlipAreCategoricalDistances() {
        DriftSignal uniform = sig(0.5, 0.5, 0.6, Distribution.UNIFORM, false, Double.NaN, Double.NaN);
        DriftSignal clustered = sig(0.5, 0.5, 0.6, Distribution.CLUSTERED, false, Double.NaN, Double.NaN);
        assertEquals(DriftSignal.DISTRIBUTION_CLASS_DISTANCE, clustered.distanceTo(uniform), 1e-9);

        DriftSignal withKeys = sig(0.5, 0.5, 0.6, Distribution.UNIFORM, true, 1000.0, 2000.0);
        assertEquals(DriftSignal.KEY_STATS_FLIP_DISTANCE, withKeys.distanceTo(uniform), 1e-9);
    }

    @Test
    void keyRangeLocationAndScaleShiftsAreNormalizedBySpan() {
        DriftSignal near = sig(0.5, 0.5, 0.6, Distribution.UNIFORM, true, 1000.0, 2000.0);
        DriftSignal farLocation = sig(0.5, 0.5, 0.6, Distribution.UNIFORM, true, 6_000_000.0, 2000.0);
        assertEquals(1.0, farLocation.distanceTo(near), 1e-9, "a huge location jump saturates to 1");

        DriftSignal widerScale = sig(0.5, 0.5, 0.6, Distribution.UNIFORM, true, 1000.0, 200_000.0);
        // span delta 198000 over denom max(2000,200000)=200000 = 0.99
        assertEquals(0.99, widerScale.distanceTo(near), 1e-6);
    }

    // ---- detector ----------------------------------------------------------

    private static final DriftSignal A =
            new DriftSignal(1000, 0.5, 0.5, 0.6, Distribution.UNIFORM, false, Double.NaN, Double.NaN);
    private static final DriftSignal FAR =
            new DriftSignal(1000, 0.5, 0.5, 0.6, Distribution.CLUSTERED, false, Double.NaN, Double.NaN); // 0.5 from A

    @Test
    void firstBatchEstablishesBaselineThenStationaryDoesNotDrift() {
        DriftDetector d = new DriftDetector(); // threshold 0.20, warmup 1, cooldown 0
        DriftVerdict first = d.test(A);
        assertTrue(first.drift(), "first batch forces the initial selection");
        assertEquals("initial baseline", first.reason());

        assertFalse(d.test(A).drift(), "identical regime is stable");
        assertFalse(d.test(A).drift());
    }

    @Test
    void driftFiresOnRegimeShiftThenRebaselines() {
        DriftDetector d = new DriftDetector();
        d.test(A);
        DriftVerdict shift = d.test(FAR);
        assertTrue(shift.drift(), "distance 0.5 >= 0.20 -> drift");
        assertTrue(shift.score() >= 0.20);
        assertFalse(d.test(FAR).drift(), "reference re-baselined to the new regime");
    }

    @Test
    void cooldownSuppressesConsecutiveFires() {
        DriftDetector d = new DriftDetector(0.20, 1, 2); // suppress 2 batches after a fire
        assertTrue(d.test(A).drift());          // initial
        assertFalse(d.test(FAR).drift());       // drift seen but suppressed (cooldown 1/2)
        assertFalse(d.test(FAR).drift());       // suppressed (2/2)
        assertTrue(d.test(FAR).drift());        // cooldown elapsed -> fire
    }

    @Test
    void warmupHoldsTheBaselineWithoutFiring() {
        DriftDetector d = new DriftDetector(0.20, 3, 0); // first 3 batches only establish the baseline
        assertTrue(d.test(A).drift());          // initial
        assertFalse(d.test(FAR).drift(), "warmup");
        assertFalse(d.test(A).drift(), "warmup");
        assertTrue(d.test(FAR).drift(), "after warmup, a real shift fires");
    }

    // ---- end-to-end driver -------------------------------------------------

    private static OrderedSet<Integer> emptySet() {
        return OrderedSet.withNaturalOrder(new RedBlackStrategy<Integer>());
    }

    private static List<Integer> sortedAsc(int n) {
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            a.add(i);
        }
        return a;
    }

    private static List<Integer> randUniform(long seed, int n, int bound) {
        Random rng = new Random(seed);
        List<Integer> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            a.add(rng.nextInt(bound));
        }
        return a;
    }

    private static List<Integer> sortedDistinctUnion(List<List<Integer>> batches) {
        TreeSet<Integer> all = new TreeSet<>();
        for (List<Integer> b : batches) {
            all.addAll(b);
        }
        return new ArrayList<>(all);
    }

    @Test
    void stationaryStreamSelectsOnceAndStaysCorrect() {
        OrderedSet<Integer> set = emptySet();
        AdaptiveStreamSorter<Integer> sorter = BeefSort.with(Comparator.<Integer>naturalOrder())
                .keyEncoder(KeyEncoder.ofInt(i -> i))
                .adaptiveStream(set, 0); // unbounded

        List<List<Integer>> batches = new ArrayList<>();
        List<StreamSortResult<Integer>> results = new ArrayList<>();
        for (int seed = 0; seed < 4; seed++) {
            List<Integer> batch = randUniform(seed, 3000, 2000); // one stable regime, different seeds
            batches.add(batch);
            results.add(sorter.accept(batch));
        }

        assertEquals(1, sorter.reselections(), "a stationary stream re-selects exactly once (no thrash)");
        assertTrue(results.get(0).reselected());
        assertFalse(results.get(1).reselected());
        assertFalse(results.get(3).reselected());
        assertEquals(sortedDistinctUnion(batches), set.inOrder(), "every distinct key retained, in order");
    }

    @Test
    void shiftingStreamReselectsPerRegimeAndStaysCorrect() {
        OrderedSet<Integer> set = emptySet();
        AdaptiveStreamSorter<Integer> sorter = BeefSort.with(Comparator.<Integer>naturalOrder())
                .keyEncoder(KeyEncoder.ofInt(i -> i))
                .adaptiveStream(set, 0); // unbounded

        List<List<Integer>> batches = List.of(
                sortedAsc(2000),                  // regime A: ~0 inversions -> insertion
                sortedAsc(2000),                  // A again -> no drift
                randUniform(1, 2000, 2000),       // regime B: bounded range -> counting
                randUniform(2, 2000, 2000),       // B again -> no drift
                randUniform(3, 2000, 5_000_000),  // regime C: wide range -> radix
                randUniform(4, 2000, 5_000_000)); // C again -> no drift

        List<StreamSortResult<Integer>> r = new ArrayList<>();
        for (List<Integer> b : batches) {
            r.add(sorter.accept(b));
        }

        assertEquals(3, sorter.reselections(), "re-selected once per regime (initial + 2 shifts)");
        assertTrue(r.get(0).reselected());
        assertFalse(r.get(1).reselected());
        assertTrue(r.get(2).reselected());
        assertFalse(r.get(3).reselected());
        assertTrue(r.get(4).reselected());
        assertFalse(r.get(5).reselected());

        Set<String> regimeStrategies = new HashSet<>();
        regimeStrategies.add(r.get(0).plan().strategy().value());
        regimeStrategies.add(r.get(2).plan().strategy().value());
        regimeStrategies.add(r.get(4).plan().strategy().value());
        assertEquals(3, regimeStrategies.size(),
                "each regime picked a distinct strategy: " + regimeStrategies);

        assertEquals(sortedDistinctUnion(batches), set.inOrder(),
                "correctness preserved across mid-stream strategy switches");
    }

    @Test
    void boundedWindowStaysBoundedAndHealthyUnderDrift() {
        OrderedSet<Integer> set = emptySet();
        int cap = 300;
        AdaptiveStreamSorter<Integer> sorter = BeefSort.with(Comparator.<Integer>naturalOrder())
                .keyEncoder(KeyEncoder.ofInt(i -> i))
                .adaptiveStream(set, cap);

        sorter.accept(sortedAsc(2000));
        sorter.accept(randUniform(1, 2000, 2000));
        StreamSortResult<Integer> last = sorter.accept(randUniform(2, 2000, 5_000_000));

        assertEquals(cap, set.getMaxSize(), "window capacity is set");
        assertEquals(cap, set.size(), "window stays bounded across regime shifts");
        assertTrue(last.feedResult().healthy(), "the streamed window stayed healthy through adaptation");
    }
}
