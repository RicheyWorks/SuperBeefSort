package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.ensemble.EnsembleOrderedSet;
import io.github.richeyworks.superbeefsort.csrbt.AccessPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * "Ensemble as a first-class target" (docs/architecture-csrbt-integration.md §4): BeefSort.buildEnsemble
 * composes a CSRBT ensemble from the profile and bulk-loads every member via the parallel mirror path.
 */
class BuildEnsembleTest {

    private static List<Integer> sample() {
        Random rng = new Random(13);
        List<Integer> a = new ArrayList<>();
        for (int i = 0; i < 6000; i++) {
            a.add(rng.nextInt(4000));
        }
        return a;
    }

    @Test
    void buildsConsistentEnsembleFromProfile() {
        EnsembleOrderedSet<Integer> ens = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(new ArrayList<>(sample()))
                .accessPattern(AccessPolicy.READ_HEAVY) // primary born AVL; RedBlack replica
                .buildEnsemble();

        List<Integer> expected = new ArrayList<>(new TreeSet<>(sample()));
        assertEquals(expected.size(), ens.size(), "ensemble size == distinct count");
        assertEquals(expected, ens.inOrder(), "ensemble in-order == sorted distinct");
        assertEquals(expected.get(0), ens.minimum());
        assertEquals(expected.get(expected.size() - 1), ens.maximum());
    }
}
