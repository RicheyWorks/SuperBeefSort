package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.TreeNode1;
import io.github.richeyworks.csrbt.control.MorphPolicy;
import io.github.richeyworks.csrbt.control.StrategyId;
import io.github.richeyworks.csrbt.ensemble.EnsembleController;
import io.github.richeyworks.csrbt.ensemble.EnsembleMember;
import io.github.richeyworks.csrbt.ensemble.EnsembleOrderedSet;
import io.github.richeyworks.csrbt.strategy.AVLStrategy;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.csrbt.strategy.SplayStrategy;
import io.github.richeyworks.superbeefsort.csrbt.EnsembleAdaptation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code EnsembleAdaptation.checkHealth()} drives CSRBT's ensemble health cadence through the adapter — the
 * failover / quarantine / heal path of Gap 1 (docs/adr-csrbt-integration-deepening.md), which
 * {@code EnsembleAdaptationTest} only smoke-covers. Mirrors CSRBT's own {@code EnsembleHealthTest}: a corrupt
 * non-primary is quarantined and healed without disturbing reads, and a structurally corrupt primary fails
 * over instantly to a healthy member. Faults are injected by reaching past the ensemble fan-out into a single
 * member's backing tree, exactly as CSRBT's test does.
 */
class EnsembleHealthAdaptationTest {

    private static EnsembleOrderedSet<Integer> rbAvlSplay() {
        return EnsembleOrderedSet.<Integer>builder(Comparator.<Integer>naturalOrder())
                .member(() -> new RedBlackStrategy<Integer>())   // initial primary
                .member(() -> new AVLStrategy<Integer>())
                .member(() -> new SplayStrategy<Integer>())
                .build();
    }

    private static EnsembleMember<Integer> memberNamed(EnsembleOrderedSet<Integer> ens, String name) {
        for (EnsembleMember<Integer> m : ens.members()) {
            if (m.strategyName().equals(name)) {
                return m;
            }
        }
        throw new AssertionError("no member backed by " + name);
    }

    @Test
    void corruptNonPrimaryIsQuarantinedAndHealedWithoutFailover() {
        EnsembleOrderedSet<Integer> ens = rbAvlSplay();
        TreeSet<Integer> oracle = new TreeSet<>();
        for (int i = 0; i < 120; i++) {
            ens.add(i);
            oracle.add(i);
        }
        EnsembleAdaptation<Integer> adapt = EnsembleAdaptation.attach(ens, MorphPolicy.defaults());

        EnsembleMember<Integer> rb = ens.primary();
        EnsembleMember<Integer> avl = memberNamed(ens, "AVLStrategy");
        // Drift the AVL member out of sync by dropping a key straight from its tree, behind the fan-out.
        assertTrue(avl.set().remove(57), "precondition: the key to drop is present");

        EnsembleController.HealthReport r = adapt.checkHealth();

        assertSame(rb, ens.primary(), "a non-primary fault must not disturb the serving primary");
        assertEquals(new ArrayList<>(oracle), ens.inOrder(), "reads stay correct throughout");
        assertEquals(1, r.quarantined(), "the drifted member is quarantined");
        assertEquals(1, r.healed(), "and healed from the primary");
        assertFalse(r.failedOver(), "no failover for a non-primary fault");
        assertEquals(0, adapt.report().failovers(), "no failover recorded");
    }

    @Test
    void corruptPrimaryFailsOverToHealthyMember() {
        EnsembleOrderedSet<Integer> ens = rbAvlSplay();
        TreeSet<Integer> oracle = new TreeSet<>();
        for (int i = 0; i < 120; i++) {
            ens.add(i);
            oracle.add(i);
        }
        EnsembleAdaptation<Integer> adapt = EnsembleAdaptation.attach(ens, MorphPolicy.defaults());
        assertEquals(StrategyId.RED_BLACK, adapt.currentPrimary(), "RB is the initial primary");

        // Corrupt the primary's structure: paint the red-black root red (a red-black root must be black).
        EnsembleMember<Integer> rb = ens.primary();
        TreeNode1<Integer> root = rb.orderedSet().getEngine().getRoot();
        root.setColor(TreeNode1.Color.RED);

        EnsembleController.HealthReport r = adapt.checkHealth();

        assertTrue(r.failedOver(), "a structurally bad primary must fail over");
        assertEquals(StrategyId.RED_BLACK, r.from(), "failed over from Red-Black");
        assertEquals(StrategyId.AVL, r.to(), "to the first healthy member (AVL)");
        assertEquals(StrategyId.AVL, adapt.currentPrimary(), "AVL now serves reads");
        assertEquals(new ArrayList<>(oracle), ens.inOrder(), "reads are correct after failover");
        assertEquals(1, adapt.report().failovers(), "the failover is recorded in the adapter report");
    }
}
