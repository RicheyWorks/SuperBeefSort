package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.control.MorphPolicy;
import io.github.richeyworks.csrbt.ensemble.EnsembleMember;
import io.github.richeyworks.csrbt.ensemble.EnsembleOrderedSet;
import io.github.richeyworks.superbeefsort.csrbt.AccessPolicy;
import io.github.richeyworks.superbeefsort.csrbt.EnsembleSpec;
import io.github.richeyworks.superbeefsort.csrbt.EnsembleTargetFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gap 2 of docs/adr-csrbt-integration-deepening.md: {@link EnsembleSpec} drives the {@link
 * EnsembleTargetFactory} member mix. LEAN reproduces the historical access-advised-primary + RedBlack-replica
 * pair; ADAPTIVE composes the promotable RedBlack + AVL + Splay morph-family trio so {@code buildAdaptiveEnsemble}
 * can actually promote; {@code withSnapshot()} adds an O(1)-snapshot persistent member.
 */
class EnsembleSpecTest {

    private static List<String> memberStrategies(EnsembleOrderedSet<Integer> ens) {
        List<String> names = new ArrayList<>();
        for (EnsembleMember<Integer> m : ens.members()) {
            names.add(m.strategyName());
        }
        return names;
    }

    @Test
    void leanMixIsAccessAdvisedPrimaryPlusRedBlackReplica() {
        // READ_HEAVY advises AVL as the primary; RedBlack is the robust replica (today's default mix).
        EnsembleOrderedSet<Integer> ens = EnsembleTargetFactory.forProfile(
                null, AccessPolicy.READ_HEAVY, Comparator.<Integer>naturalOrder(), EnsembleSpec.lean());
        assertEquals(List.of("AVLStrategy", "RedBlackStrategy"), memberStrategies(ens));
    }

    @Test
    void adaptiveMixIsThePromotableRedBlackAvlSplayTrio() {
        EnsembleOrderedSet<Integer> ens = EnsembleTargetFactory.forProfile(
                null, AccessPolicy.BALANCED, Comparator.<Integer>naturalOrder(), EnsembleSpec.adaptive());
        assertEquals(List.of("RedBlackStrategy", "AVLStrategy", "SplayStrategy"), memberStrategies(ens));
    }

    @Test
    void withSnapshotAddsAPersistentMember() {
        EnsembleOrderedSet<Integer> ens = EnsembleTargetFactory.forProfile(
                null, AccessPolicy.BALANCED, Comparator.<Integer>naturalOrder(),
                EnsembleSpec.adaptive().withSnapshot());
        List<String> names = memberStrategies(ens);
        assertEquals(4, names.size(), names.toString());
        assertEquals(List.of("RedBlackStrategy", "AVLStrategy", "SplayStrategy"), names.subList(0, 3));
        assertTrue(names.get(3).contains("Persistent"), "snapshot member should be the persistent engine: " + names.get(3));
    }

    @Test
    void buildAdaptiveEnsembleDefaultsToThePromotableTrio() {
        // The facade's adaptive build yields a 3-member promotable ensemble out of the box (Gap 1 + Gap 2),
        // where the old default (LEAN, often RedBlack + RedBlack) had nowhere to promote.
        List<Integer> data = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            data.add(i);
        }
        EnsembleOrderedSet<Integer> ens = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(data)
                .buildAdaptiveEnsemble(MorphPolicy.defaults())
                .ensemble();
        assertEquals(List.of("RedBlackStrategy", "AVLStrategy", "SplayStrategy"), memberStrategies(ens));
        assertEquals(500, ens.size(), "every member mirrors the de-duplicated sorted input");
    }
}
