package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.control.MorphController;
import io.github.richeyworks.csrbt.control.MorphPolicy;
import io.github.richeyworks.csrbt.control.StrategyId;
import io.github.richeyworks.csrbt.control.StrategyScorer;
import io.github.richeyworks.csrbt.control.WorkloadFeatures;
import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.csrbt.AccessPolicy;
import io.github.richeyworks.superbeefsort.csrbt.ProfileGuidedScorer;
import io.github.richeyworks.superbeefsort.csrbt.WorkloadAdaptation;
import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.profile.Distribution;
import io.github.richeyworks.superbeefsort.profile.ProfileDepth;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Co-optimization — "two engines talking" (docs/architecture-csrbt-integration.md §5): SuperBeefSort's
 * data profile biases CSRBT's tree-strategy decision via {@link ProfileGuidedScorer}, and
 * {@code BeefSort.buildCoOptimized} both builds the tree in the profile-favored shape and primes its
 * adaptation with that prior. Covered at three levels: the profile→strategy mapping, the prior's effect on
 * ranking (against a stub base scorer, so the assertion is independent of CSRBT's cost-model internals),
 * and the end-to-end build against a real CSRBT {@code OrderedSet}.
 */
class CoOptimizationTest {

    private static DataProfile profileWith(Distribution dist) {
        return new DataProfile(1000, 0.5, false, ProfileDepth.SHALLOW, 1000, null, dist);
    }

    // ---- profile -> favored strategy mapping -------------------------------

    @Test
    void favoredStrategyMapsAccessThenDistribution() {
        assertEquals(StrategyId.AVL,
                ProfileGuidedScorer.favoredStrategy(profileWith(Distribution.UNIFORM), AccessPolicy.READ_HEAVY));
        assertEquals(StrategyId.SPLAY,
                ProfileGuidedScorer.favoredStrategy(profileWith(Distribution.UNIFORM), AccessPolicy.SKEWED));
        assertEquals(StrategyId.RED_BLACK,
                ProfileGuidedScorer.favoredStrategy(profileWith(Distribution.UNIFORM), AccessPolicy.WRITE_HEAVY));
        assertEquals(StrategyId.RED_BLACK,
                ProfileGuidedScorer.favoredStrategy(profileWith(Distribution.UNIFORM), AccessPolicy.BALANCED));
        // For an unspecified access pattern the key distribution breaks the tie: clustered -> Splay.
        assertEquals(StrategyId.SPLAY,
                ProfileGuidedScorer.favoredStrategy(profileWith(Distribution.CLUSTERED), AccessPolicy.BALANCED));
    }

    // ---- prior re-ranking (stub base scorer) -------------------------------

    /** A deterministic base scorer with hand-set costs, independent of CSRBT's cost model. */
    private static StrategyScorer stub(double rb, double avl, double splay, double hybrid) {
        return features -> {
            List<StrategyScorer.Score> l = new ArrayList<>();
            l.add(new StrategyScorer.Score(StrategyId.RED_BLACK, rb, "rb"));
            l.add(new StrategyScorer.Score(StrategyId.AVL, avl, "avl"));
            l.add(new StrategyScorer.Score(StrategyId.SPLAY, splay, "splay"));
            l.add(new StrategyScorer.Score(StrategyId.HYBRID, hybrid, "hybrid"));
            l.sort(Comparator.comparingDouble(StrategyScorer.Score::estimatedCost));
            return l;
        };
    }

    @Test
    void priorFlipsAMildContraryRanking() {
        // Base mildly prefers RB (10) over AVL (11); a 15% prior on AVL -> 9.35 -> AVL becomes cheapest.
        ProfileGuidedScorer guided = new ProfileGuidedScorer(stub(10, 11, 12, 13), StrategyId.AVL, 0.15);
        List<StrategyScorer.Score> ranked = guided.score(WorkloadFeatures.EMPTY);

        assertEquals(StrategyId.AVL, ranked.get(0).strategy());
        assertEquals(9.35, ranked.get(0).estimatedCost(), 1e-9);
        assertTrue(ranked.get(0).rationale().contains("profile-prior"));
        assertEquals(4, ranked.size(), "every strategy still scored, none dropped");
    }

    @Test
    void priorDoesNotOverrideAClearWinner() {
        // Base strongly prefers RB (10) over AVL (100); 15% prior -> AVL 85, still > 10 -> RB stays first.
        ProfileGuidedScorer guided = new ProfileGuidedScorer(stub(10, 100, 120, 130), StrategyId.AVL, 0.15);
        assertEquals(StrategyId.RED_BLACK, guided.score(WorkloadFeatures.EMPTY).get(0).strategy(),
                "the prior is a nudge, not an override");
    }

    @Test
    void rejectsInvalidPrior() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProfileGuidedScorer(stub(1, 1, 1, 1), StrategyId.AVL, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new ProfileGuidedScorer(stub(1, 1, 1, 1), StrategyId.AVL, -0.1));
    }

    // ---- end-to-end buildCoOptimized ---------------------------------------

    private static List<Integer> sample() {
        Random rng = new Random(17);
        List<Integer> a = new ArrayList<>();
        for (int i = 0; i < 4000; i++) {
            a.add(rng.nextInt(3000)); // duplicates -> the build de-dups
        }
        return a;
    }

    /** No cooldown, any positive improvement, one stability win — a clear winner morphs promptly. */
    private static MorphPolicy relaxed() {
        return new MorphPolicy(0, 0.0, 1);
    }

    @Test
    void coOptimizedIsBornFromTheProfileAndStaysCorrect() {
        List<Integer> data = sample();
        WorkloadAdaptation<Integer> wa = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(data)
                .keyEncoder(KeyEncoder.ofInt(i -> i))
                .accessPattern(AccessPolicy.READ_HEAVY)
                .buildCoOptimized(relaxed());

        assertEquals(StrategyId.AVL, wa.currentStrategy(),
                "READ_HEAVY -> born AVL (the profile-favored morph-family strategy)");
        assertEquals(new ArrayList<>(new TreeSet<>(data)), wa.set().inOrder(),
                "the co-optimized build holds exactly the sorted distinct keys");
    }

    @Test
    void profilePriorHoldsTheBornStrategyUnderMatchingWorkload() {
        WorkloadAdaptation<Integer> wa = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(sample())
                .keyEncoder(KeyEncoder.ofInt(i -> i))
                .accessPattern(AccessPolicy.READ_HEAVY)
                .buildCoOptimized(relaxed());

        // Read-heavy, low-skew lookups: both the live cost model and the profile prior favor AVL.
        for (int k = 0; k < 2000; k++) {
            wa.recordSearch(k);
        }
        MorphController.MorphResult r = wa.maybeAdapt();

        assertEquals(StrategyId.AVL, wa.currentStrategy(), "AVL stays optimal for read-heavy, low-skew");
        assertTrue(!r.morphed() || r.to() == StrategyId.AVL, "no morph away from the agreed-optimal AVL");
    }

    @Test
    void writeHeavyCoOptimizedIsAdaptableRedBlackUnlikeBuildAdaptive() {
        // buildAdaptive(WRITE_HEAVY) advises the static weight-balanced shape and is rejected as non-adaptive
        // (see WorkloadAdaptationTest); the co-optimized path instead stays in the morph family (Red-Black).
        WorkloadAdaptation<Integer> wa = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(sample())
                .keyEncoder(KeyEncoder.ofInt(i -> i))
                .accessPattern(AccessPolicy.WRITE_HEAVY)
                .buildCoOptimized(relaxed());

        assertEquals(StrategyId.RED_BLACK, wa.currentStrategy(),
                "WRITE_HEAVY co-optimized uses morph-family Red-Black, so the tree can still adapt");
    }
}
