package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.control.MorphController;
import io.github.richeyworks.csrbt.control.MorphPolicy;
import io.github.richeyworks.csrbt.control.StrategyId;
import io.github.richeyworks.superbeefsort.csrbt.AccessPolicy;
import io.github.richeyworks.superbeefsort.csrbt.WorkloadAdaptation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Born optimal AND wired to adapt" (docs/architecture-csrbt-integration.md §5): a Red-Black-born set,
 * once wired to CSRBT's control plane via {@link WorkloadAdaptation}, re-tunes its balancing strategy to
 * the live access pattern. The CSRBT cost model is closed-form, so each morph here is deterministic:
 * uniform read pressure makes AVL cheapest (strict balance → fewest comparisons); a single hot key makes
 * Splay cheapest (self-adjusts the hot key toward the root). A relaxed {@link MorphPolicy} removes the
 * production cooldown/margin so the morph fires inside the test.
 */
class WorkloadAdaptationTest {

    /** No cooldown, any positive improvement, one stability win — so a clear winner morphs promptly. */
    private static MorphPolicy relaxed() {
        return new MorphPolicy(0, 0.0, 1);
    }

    private static List<Integer> sample() {
        Random rng = new Random(17);
        List<Integer> a = new ArrayList<>();
        for (int i = 0; i < 4000; i++) {
            a.add(rng.nextInt(3000)); // duplicates -> the build de-dups
        }
        return a;
    }

    private static WorkloadAdaptation<Integer> redBlackBornAdaptive() {
        WorkloadAdaptation<Integer> adapt = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(sample())
                .accessPattern(AccessPolicy.BALANCED) // born Red-Black
                .buildAdaptive(relaxed());
        assertEquals(StrategyId.RED_BLACK, adapt.currentStrategy(), "BALANCED is born Red-Black");
        return adapt;
    }

    /** Run up to {@code maxEvals} adaptation cycles, returning true as soon as one morphs. */
    private static boolean adaptUntilMorph(WorkloadAdaptation<Integer> adapt, int maxEvals) {
        for (int i = 0; i < maxEvals; i++) {
            MorphController.MorphResult r = adapt.maybeAdapt();
            if (r.morphed()) {
                return true;
            }
        }
        return false;
    }

    @Test
    void uniformReadPressureMorphsRedBlackToAvl() {
        WorkloadAdaptation<Integer> adapt = redBlackBornAdaptive();
        List<Integer> before = adapt.set().inOrder();

        // Many distinct keys, read-only -> readFraction ~ 1.0, accessSkew ~ 0.0 -> AVL is cheapest.
        for (int pass = 0; pass < 2; pass++) {
            for (int k = 0; k < 2000; k++) {
                adapt.recordSearch(k);
            }
        }

        assertTrue(adaptUntilMorph(adapt, 6), "uniform read workload should trigger a morph");
        assertEquals(StrategyId.AVL, adapt.currentStrategy(), "read-heavy, low-skew -> AVL");
        assertEquals(before, adapt.set().inOrder(), "the in-place morph preserves contents");
    }

    @Test
    void singleHotKeyMorphsRedBlackToSplay() {
        WorkloadAdaptation<Integer> adapt = redBlackBornAdaptive();
        List<Integer> before = adapt.set().inOrder();

        // One hammered key -> accessSkew ~ 1.0, read-only -> Splay is cheapest.
        for (int i = 0; i < 5000; i++) {
            adapt.recordSearch(0xBEEF);
        }

        assertTrue(adaptUntilMorph(adapt, 6), "a hot-key read workload should trigger a morph");
        assertEquals(StrategyId.SPLAY, adapt.currentStrategy(), "skewed reads -> Splay");
        assertEquals(before, adapt.set().inOrder(), "the in-place morph preserves contents");
    }

    @Test
    void onceOptimalItHoldsInsteadOfThrashing() {
        WorkloadAdaptation<Integer> adapt = redBlackBornAdaptive();
        for (int k = 0; k < 2000; k++) {
            adapt.recordSearch(k);
        }
        assertTrue(adaptUntilMorph(adapt, 6)); // RB -> AVL
        assertEquals(StrategyId.AVL, adapt.currentStrategy());

        // Same diet continues: AVL is already optimal, so further evaluations hold (no churn).
        for (int k = 0; k < 2000; k++) {
            adapt.recordSearch(k);
        }
        MorphController.MorphResult r = adapt.maybeAdapt();
        assertTrue(!r.morphed(), "an already-optimal incumbent must not morph");
        assertEquals(StrategyId.AVL, adapt.currentStrategy());
    }

    @Test
    void weightBalancedTargetIsNotAdaptable() {
        // WRITE_HEAVY advises WeightBalanced, which has no StrategyId -> adaptation is rejected up front.
        assertThrows(IllegalArgumentException.class, () ->
                BeefSort.with(Comparator.<Integer>naturalOrder())
                        .source(sample())
                        .accessPattern(AccessPolicy.WRITE_HEAVY)
                        .buildAdaptive(MorphPolicy.defaults()));
    }
}
