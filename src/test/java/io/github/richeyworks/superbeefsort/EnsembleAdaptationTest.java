package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.control.CostModelStrategyScorer;
import io.github.richeyworks.csrbt.control.MorphPolicy;
import io.github.richeyworks.csrbt.control.RollingWorkloadMonitor;
import io.github.richeyworks.csrbt.control.StrategyId;
import io.github.richeyworks.csrbt.ensemble.EnsembleController;
import io.github.richeyworks.csrbt.ensemble.EnsembleOrderedSet;
import io.github.richeyworks.csrbt.strategy.AVLStrategy;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.csrbt.strategy.SplayStrategy;
import io.github.richeyworks.superbeefsort.csrbt.EnsembleAdaptation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gap 1 of docs/adr-csrbt-integration-deepening.md: an {@link EnsembleOrderedSet} wired to CSRBT's ensemble
 * control plane via {@link EnsembleAdaptation} migrates the read path to the member matching the live
 * workload — an O(1) primary swap (promotion), not an O(n) morph. This mirrors CSRBT's own
 * {@code EnsembleControllerTest.skewedReadsPromoteSplayWithoutRebuild} (a skewed read stream promotes the
 * Splay member exactly once), routed through SuperBeefSort's adapter.
 */
class EnsembleAdaptationTest {

    @Test
    void skewedReadsPromoteReadPathToSplay() {
        EnsembleOrderedSet<Integer> ens = EnsembleOrderedSet.builder(Comparator.<Integer>naturalOrder())
                .member(() -> new RedBlackStrategy<Integer>())   // initial primary
                .member(() -> new AVLStrategy<Integer>())
                .member(() -> new SplayStrategy<Integer>())
                .build();
        // Mirror CSRBT's proven setup: small decay window so the read burst dominates; eager-but-stable
        // policy (no cooldown, 20% margin, 2 consecutive wins).
        EnsembleAdaptation<Integer> adapt = EnsembleAdaptation.attach(
                ens, new RollingWorkloadMonitor(512), new CostModelStrategyScorer(), new MorphPolicy(0, 0.20, 2));
        assertEquals(StrategyId.RED_BLACK, adapt.currentPrimary(), "RB is the initial primary");

        for (int i = 0; i < 200; i++) {
            adapt.add(i); // seed population (effective writes, recorded through the adapter)
        }

        int promotions = 0;
        StrategyId promotedTo = null;
        for (int round = 0; round < 12; round++) {
            for (int i = 0; i < 300; i++) {
                adapt.contains(7); // heavily skewed, read-dominated: hammer one hot key
            }
            EnsembleController.PromotionResult r = adapt.maybePromote();
            if (r.promoted()) {
                promotions++;
                promotedTo = r.to();
            }
        }

        assertEquals(1, promotions, "a skewed read stream should promote exactly once");
        assertEquals(StrategyId.SPLAY, promotedTo, "the promotion target is the Splay member");
        assertEquals(StrategyId.SPLAY, adapt.currentPrimary(), "Splay now serves reads");
        assertEquals(1, adapt.report().promotions(), adapt.report().summary());
        assertEquals(200, ens.size(), "the O(1) primary swap leaves contents untouched");
    }

    @Test
    void buildAdaptiveEnsembleAttachesTheControllerAndOpsFlow() {
        // The facade wires a profile-composed ensemble to the controller without throwing; live ops flow
        // through the adapter and an evaluation runs (the default RB+RB mix won't promote — that's Gap 2).
        EnsembleAdaptation<Integer> adapt = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(new ArrayList<>(List.of(5, 1, 9, 3, 7, 2, 8, 4, 6)))
                .buildAdaptiveEnsemble(MorphPolicy.defaults());

        assertTrue(adapt.contains(5));
        assertTrue(adapt.add(42));
        adapt.maybePromote();
        assertEquals(1, adapt.report().evaluations());
    }
}
