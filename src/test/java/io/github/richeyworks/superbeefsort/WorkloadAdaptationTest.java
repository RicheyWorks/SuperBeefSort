package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.control.MorphController;
import io.github.richeyworks.csrbt.control.MorphPolicy;
import io.github.richeyworks.csrbt.control.StrategyId;
import io.github.richeyworks.superbeefsort.csrbt.AccessPolicy;
import io.github.richeyworks.superbeefsort.csrbt.AdaptationReport;
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
 *
 * <p>Read-diet volumes are pinned to the 2026-07-14 scorer recalibration: {@code buildAdaptive} folds
 * the construction feed (~2210 distinct keys) into the 4096-op rolling window as writes, and the
 * recalibrated Hybrid undercuts AVL while {@code writeFraction > ~0.08} — the feed keeps w above that
 * until ~7.2k subsequent reads have decayed it. Read diets therefore run 10k reads before expecting
 * AVL, so the window genuinely reflects the "read-only" story each test tells. (Under production
 * gates the feed residue never morphs anything — the near-tie is ~1% deep, inside the 20% margin —
 * this is purely a zero-margin-test consideration.)
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

        // Many distinct keys, read-only -> readFraction -> 1.0, accessSkew ~ 0.0 -> AVL is cheapest.
        // Volume matters: buildAdaptive folds the ~2210-key construction feed into the 4096-op
        // rolling window as writes, and post-recalibration (2026-07-14) Hybrid undercuts AVL while
        // w > ~0.08 — true here until ~7.2k reads have decayed the feed. 10k reads land at
        // w ~ 0.038, where AVL is cheapest again (0.3461 vs Hybrid 0.3472; RB 0.3699, a 6.4%
        // RB->AVL improvement, so the relaxed policy's zero margin clears with room).
        for (int pass = 0; pass < 5; pass++) {
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
        for (int pass = 0; pass < 5; pass++) { // 10k reads: decays the build feed below w ~ 0.08,
            for (int k = 0; k < 2000; k++) {   // where AVL undercuts Hybrid (see the uniform test)
                adapt.recordSearch(k);
            }
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

    @Test
    void bornRightHoldsWithZeroMorphsOnMatchingWorkload() {
        // The §5 success metric, now an assertable guardrail: READ_HEAVY advises AVL, and under uniform
        // read pressure AVL is already cheapest (see uniformReadPressureMorphsRedBlackToAvl), so a tree
        // BORN AVL never has to morph on a matching workload — adaptationReport().held() captures that.
        WorkloadAdaptation<Integer> adapt = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(sample())
                .accessPattern(AccessPolicy.READ_HEAVY) // born AVL
                .buildAdaptive(relaxed());
        assertEquals(StrategyId.AVL, adapt.currentStrategy(), "READ_HEAVY is born AVL");

        for (int pass = 0; pass < 3; pass++) {
            for (int rep = 0; rep < 5; rep++) {  // 10k reads per pass: decays the build feed below
                for (int k = 0; k < 2000; k++) { // the w ~ 0.08 Hybrid crossover BEFORE the first
                    adapt.recordSearch(k);       // evaluation (w ~ 0.038, 0.003, 0.0003 per eval)
                }
            }
            adapt.maybeAdapt(); // uniform reads keep AVL cheapest -> no morph
        }

        AdaptationReport report = adapt.adaptationReport();
        assertTrue(report.held(), "a born-right tree must hold on a matching workload: " + report.summary());
        assertEquals(0, report.morphs());
        assertEquals(3, report.evaluations());
        assertEquals(StrategyId.AVL, adapt.currentStrategy(), "still AVL after the matching workload");
    }
}
