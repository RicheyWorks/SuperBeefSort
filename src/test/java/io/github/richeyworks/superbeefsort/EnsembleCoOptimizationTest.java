package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.control.MorphPolicy;
import io.github.richeyworks.csrbt.control.RollingWorkloadMonitor;
import io.github.richeyworks.csrbt.control.StrategyId;
import io.github.richeyworks.csrbt.ensemble.EnsembleController;
import io.github.richeyworks.csrbt.ensemble.EnsembleOrderedSet;
import io.github.richeyworks.csrbt.strategy.AVLStrategy;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.csrbt.strategy.SplayStrategy;
import io.github.richeyworks.superbeefsort.core.SortResult;
import io.github.richeyworks.superbeefsort.csrbt.AccessPolicy;
import io.github.richeyworks.superbeefsort.csrbt.EnsembleAdaptation;
import io.github.richeyworks.superbeefsort.csrbt.ProfileGuidedScorer;
import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.profile.Distribution;
import io.github.richeyworks.superbeefsort.profile.ProfileDepth;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ensemble analog of {@link CoOptimizationTest}'s Gap 5 work: {@code BeefSort.buildCoOptimizedEnsemble}
 * builds a promotable ensemble and wires its read-path promotion with a {@link ProfileGuidedScorer} prior
 * whose strength is derived from the realized sort run. Covered two ways: the facade builds a working,
 * controller-attached ensemble; and a run-derived-prior-guided controller actually migrates the read path to
 * the profile-favored member under a matching workload (mirroring {@link EnsembleAdaptationTest}'s proven
 * skewed-reads-promote-Splay setup, but through the co-optimization scorer).
 */
class EnsembleCoOptimizationTest {

    private static Comparator<Integer> nat() {
        return Comparator.naturalOrder();
    }

    // ---- facade: build + ops flow ------------------------------------------

    @Test
    void buildCoOptimizedEnsembleAttachesAControllerAndOpsFlow() {
        EnsembleAdaptation<Integer> adapt = BeefSort.with(nat())
                .source(new ArrayList<>(List.of(5, 1, 9, 3, 7, 2, 8, 4, 6)))
                .accessPattern(AccessPolicy.SKEWED)
                .buildCoOptimizedEnsemble(MorphPolicy.defaults());

        assertEquals(9, adapt.ensemble().size(), "the 9 distinct sorted keys were bulk-loaded into every member");
        assertTrue(adapt.contains(5));
        assertFalse(adapt.contains(99));
        assertTrue(adapt.add(42));
        assertEquals(10, adapt.ensemble().size(), "a live add flows through the adapter");

        adapt.maybePromote();
        assertEquals(1, adapt.report().evaluations(), "one evaluation cycle ran without throwing");
    }

    // ---- the run-derived prior steers ensemble promotion -------------------

    @Test
    void runDerivedPriorGuidesPromotionToTheFavoredMember() {
        EnsembleOrderedSet<Integer> ens = EnsembleOrderedSet.builder(nat())
                .member(() -> new RedBlackStrategy<Integer>())   // initial primary
                .member(() -> new AVLStrategy<Integer>())
                .member(() -> new SplayStrategy<Integer>())
                .build();

        // SKEWED access -> the profile favors Splay; a clean, exactly-measured run makes the prior toward it
        // strong (favoredStrategy(profile, SKEWED) == SPLAY regardless of the profile's other fields).
        DataProfile profile = new DataProfile(
                2000, 0.5, false, ProfileDepth.DEEP, 2000, null, Distribution.UNIFORM, 0, 100, true);
        SortResult metrics = new SortResult(
                io.github.richeyworks.superbeefsort.core.StrategyId.of("test"), 2000, 500, 0L, 0L, 0L);

        // Mirror CSRBT's proven promotion conditions: small decay window, eager-but-stable policy.
        EnsembleAdaptation<Integer> adapt = EnsembleAdaptation.attach(
                ens, new RollingWorkloadMonitor(512),
                ProfileGuidedScorer.forRun(profile, AccessPolicy.SKEWED, metrics),
                new MorphPolicy(0, 0.20, 2));
        assertEquals(StrategyId.RED_BLACK, adapt.currentPrimary(), "RB is the initial primary");

        for (int i = 0; i < 200; i++) {
            adapt.add(i); // seed population
        }

        int promotions = 0;
        StrategyId promotedTo = null;
        for (int round = 0; round < 12; round++) {
            for (int i = 0; i < 300; i++) {
                adapt.contains(7); // heavily skewed, read-dominated
            }
            EnsembleController.PromotionResult r = adapt.maybePromote();
            if (r.promoted()) {
                promotions++;
                promotedTo = r.to();
            }
        }

        assertTrue(promotions >= 1, "a skewed read stream should promote the read path off RB");
        assertEquals(StrategyId.SPLAY, promotedTo, "the read path migrates to the profile-favored Splay member");
        assertEquals(StrategyId.SPLAY, adapt.currentPrimary(), "Splay now serves reads");
        assertEquals(200, ens.size(), "the O(1) primary swap leaves contents untouched");
    }
}
