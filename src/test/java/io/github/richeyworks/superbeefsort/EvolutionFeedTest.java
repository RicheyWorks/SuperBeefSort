package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.control.MorphPolicy;
import io.github.richeyworks.csrbt.ensemble.EnsembleOrderedSet;
import io.github.richeyworks.csrbt.evolution.PolicyGenome;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.superbeefsort.csrbt.AccessPolicy;
import io.github.richeyworks.superbeefsort.csrbt.EnsembleTargetFactory;
import io.github.richeyworks.superbeefsort.csrbt.EvolutionAdaptation;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Gap-7 evolution feed: SuperBeefSort driving CSRBT's evolution machine through
 * {@link EvolutionAdaptation} — bandit trials (V3) and (μ+λ) generations (V4) on laboratory members
 * of an {@link EnsembleTargetFactory#evolutionHost} host, caller-cadenced against live traffic.
 */
class EvolutionFeedTest {

    private static void churn(EvolutionAdaptation<Integer> evo, Random rnd, int ops) {
        for (int i = 0; i < ops; i++) {
            int key = rnd.nextInt(800);
            if (rnd.nextInt(100) < 55) {
                evo.add(key);
            } else {
                evo.remove(key);
            }
            if (i % 4 == 0) {
                evo.contains(rnd.nextInt(800));
            }
        }
    }

    @Test
    void banditTrialCycleRunsScoresAndReports() {
        EnsembleOrderedSet<Integer> host = EnsembleTargetFactory.evolutionHost(
                null, AccessPolicy.BALANCED, Comparator.<Integer>naturalOrder(), 1, false);
        EvolutionAdaptation<Integer> evo = EvolutionAdaptation.banditSearch(host, MorphPolicy.defaults());
        assertEquals(EvolutionAdaptation.Mode.BANDIT_SEARCH, evo.mode());

        for (int i = 0; i < 400; i++) {
            evo.add(i);
        }
        List<PolicyGenome> arms = evo.beginCycle();
        assertEquals(1, arms.size(), "the bandit trials one arm per window");

        churn(evo, new Random(7), 600);
        EvolutionAdaptation.CycleResult result = evo.endCycle();

        assertEquals(EvolutionAdaptation.Mode.BANDIT_SEARCH, result.mode());
        assertNotNull(result.reason(), "the verdict is explainable in one line");
        assertEquals(1, evo.cycles());
        host.close();
    }

    @Test
    void populationGenerationsBreedFoundersFirst() {
        EnsembleOrderedSet<Integer> host = EnsembleTargetFactory.evolutionHost(
                null, AccessPolicy.BALANCED, Comparator.<Integer>naturalOrder(), 3, true);
        EvolutionAdaptation<Integer> evo = EvolutionAdaptation.population(
                host, MorphPolicy.defaults(), EvolutionAdaptation.defaultFounders(), 2, false, 42L);
        assertEquals(EvolutionAdaptation.Mode.POPULATION, evo.mode());

        for (int i = 0; i < 400; i++) {
            evo.add(i);
        }

        List<PolicyGenome> gen0 = evo.beginCycle();
        assertEquals(EvolutionAdaptation.defaultFounders().size(), gen0.size(),
                "generation 0 evaluates the founders");
        churn(evo, new Random(11), 800);
        EvolutionAdaptation.CycleResult first = evo.endCycle();
        assertNotNull(first.reason());

        List<PolicyGenome> gen1 = evo.beginCycle();
        assertFalse(gen1.isEmpty(), "survivors breed the next generation");
        churn(evo, new Random(13), 800);
        evo.endCycle();

        assertEquals(2, evo.cycles());
        host.close();
    }

    @Test
    void hostWithoutLaboratoriesIsRejected() {
        // Primary + an engine member only: no strategy-backed laboratory for evolution to shape.
        EnsembleOrderedSet<Integer> host = EnsembleOrderedSet.<Integer>builder(Comparator.naturalOrder())
                .member(() -> new RedBlackStrategy<Integer>())
                .persistentMember()
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> EvolutionAdaptation.banditSearch(host, MorphPolicy.defaults()));
        host.close();
    }

    @Test
    void defaultFoundersAreDistinctAndInBox() {
        List<PolicyGenome> founders = EvolutionAdaptation.defaultFounders();
        assertEquals(founders.size(), founders.stream().distinct().count(), "founders must be distinct");
        for (PolicyGenome g : founders) {
            assertTrue(g.inVerifiedBox(), g + " must be inside the verified box");
        }
    }
}
