package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.control.MorphPolicy;
import io.github.richeyworks.csrbt.control.RollingWorkloadMonitor;
import io.github.richeyworks.csrbt.control.WorkloadFeatures;
import io.github.richeyworks.csrbt.ensemble.EnsembleMode;
import io.github.richeyworks.csrbt.ensemble.EnsembleOrderedSet;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.csrbt.strategy.WeightBalancedStrategy;
import io.github.richeyworks.superbeefsort.csrbt.AccessPolicy;
import io.github.richeyworks.superbeefsort.csrbt.EnsembleSpec;
import io.github.richeyworks.superbeefsort.csrbt.EnsembleTargetFactory;
import io.github.richeyworks.superbeefsort.csrbt.StrategyAdvisor;
import io.github.richeyworks.superbeefsort.csrbt.WorkloadAdaptation;
import io.github.richeyworks.superbeefsort.feed.BulkBuildFeeder;
import io.github.richeyworks.superbeefsort.feed.CsrbtTarget;
import io.github.richeyworks.superbeefsort.feed.StreamingFeeder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The audit-driven feeding seams (docs/audit-csrbt-feeding-2026-07-07.md): feeds visible to the
 * control plane (Gap 1), realized depth/rotation signals (Gap 2), loud bounded-stream failure on
 * windowless targets (Gap 8), the grown {@link EnsembleSpec} knobs (Gap 4), and profile-tuned
 * weight balance (Gap 6).
 */
class FeedingSeamsTest {

    private static List<Integer> ascending(int n) {
        List<Integer> keys = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            keys.add(i);
        }
        return keys;
    }

    @Test
    void feedTrafficReachesAnAttachedMonitor() {
        RollingWorkloadMonitor monitor = new RollingWorkloadMonitor();
        OrderedSet<Integer> set = OrderedSet.withNaturalOrder(new RedBlackStrategy<Integer>());
        CsrbtTarget<Integer> target = CsrbtTarget.of(set).observedBy(monitor);

        new BulkBuildFeeder<Integer>().feed(ascending(500), target);

        assertEquals(500, set.size());
        WorkloadFeatures f = monitor.snapshot();
        assertTrue(f.writeFraction() > 0.0,
                "a bulk feed must register as write traffic; features were " + f);
    }

    @Test
    void workloadAdaptationFacadeRecordsRealDepthAndRotations() {
        OrderedSet<Integer> set = OrderedSet.withNaturalOrder(new RedBlackStrategy<Integer>());
        WorkloadAdaptation<Integer> adapt = WorkloadAdaptation.attach(set, MorphPolicy.defaults());

        for (int i = 0; i < 128; i++) {
            assertTrue(adapt.add(i));       // ascending adds incur rotations -> recorded per write
        }
        for (int i = 0; i < 64; i++) {
            assertTrue(adapt.contains(i));  // one walk: answers AND measures depth
        }
        assertFalse(adapt.contains(10_000));

        WorkloadFeatures f = adapt.monitor().snapshot();
        assertTrue(f.meanSearchDepth() > 0.0,
                "realized depths must reach the feature vector; features were " + f);
        assertTrue(f.rotationsPerWrite() > 0.0,
                "realized rotations must reach the feature vector; features were " + f);
    }

    @Test
    void boundedStreamingIntoWindowlessTargetFailsLoudly() {
        // withSnapshot() adds an engine-tier member, which has no sliding window — so this
        // ensemble is genuinely windowless even after CSRBT's 2026-07-08 ensemble-window seam.
        EnsembleOrderedSet<Integer> ensemble = EnsembleTargetFactory.forProfile(
                null, AccessPolicy.BALANCED, Comparator.<Integer>naturalOrder(),
                EnsembleSpec.lean().withSnapshot());
        CsrbtTarget<Integer> target = CsrbtTarget.of(ensemble);

        assertThrows(IllegalArgumentException.class,
                () -> new StreamingFeeder<Integer>(10).feed(ascending(100), target),
                "a bounded stream into a windowless target must not silently run unbounded");
        ensemble.close();
    }

    @Test
    void boundedStreamingIntoAllStrategyEnsembleWindowsUniformly() {
        // The follow-up the loud failure pointed at, now closed: an all-strategy-member ensemble
        // supports the window, so a bounded stream converges every mirror to the same top-N.
        EnsembleOrderedSet<Integer> ensemble = EnsembleTargetFactory.forProfile(
                null, AccessPolicy.BALANCED, Comparator.<Integer>naturalOrder(), EnsembleSpec.lean());
        new StreamingFeeder<Integer>(10).feed(ascending(100), CsrbtTarget.of(ensemble));

        assertEquals(10, ensemble.size(), "window bound holds across the ensemble");
        assertEquals(90, ensemble.minimum().intValue(), "converged to the largest 10 distinct keys");
        assertEquals(99, ensemble.maximum().intValue());
        ensemble.close();
    }

    @Test
    void ensembleSpecKnobsReachTheBuilder() {
        EnsembleSpec spec = EnsembleSpec.adaptive().verified(7).withMemoryCeiling(1L << 20);
        EnsembleOrderedSet<Integer> ensemble = EnsembleTargetFactory.forProfile(
                null, AccessPolicy.BALANCED, Comparator.<Integer>naturalOrder(), spec);

        assertEquals(EnsembleMode.VERIFIED, ensemble.mode());
        assertEquals(7, ensemble.verifyEvery());
        assertEquals(1L << 20, ensemble.memoryCeilingBytes());
        assertEquals(3, ensemble.members().size(), "the adaptive trio");
        ensemble.close();
    }

    @Test
    void navigableSetFlavorIsADropInView() {
        java.util.NavigableSet<Integer> nav = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(new ArrayList<>(List.of(5, 3, 9, 1, 7)))
                .buildNavigableSet();
        assertEquals(5, nav.size());
        assertEquals(1, nav.first().intValue());
        assertEquals(9, nav.last().intValue());
        assertEquals(5, nav.ceiling(4).intValue());
        assertEquals(3, nav.floor(4).intValue());
        assertTrue(nav.contains(7));
    }

    @Test
    void writeHeavyAdviceIsProfileTunedWeightBalance() {
        // No profile: the literature default WB(3,2).
        var advised = StrategyAdvisor.<Integer>advise(null, AccessPolicy.WRITE_HEAVY);
        assertTrue(advised instanceof WeightBalancedStrategy, "WRITE_HEAVY advises weight balance");
        WeightBalancedStrategy<?> wb = (WeightBalancedStrategy<?>) advised;
        assertEquals(WeightBalancedStrategy.DEFAULT_DELTA, wb.delta());
        assertEquals(WeightBalancedStrategy.DEFAULT_RATIO, wb.ratio());
    }
}
