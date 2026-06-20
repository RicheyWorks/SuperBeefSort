package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.superbeefsort.core.SortResult;
import io.github.richeyworks.superbeefsort.core.StrategyId;
import io.github.richeyworks.superbeefsort.csrbt.AccessPolicy;
import io.github.richeyworks.superbeefsort.csrbt.ProfileGuidedScorer;
import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.profile.Distribution;
import io.github.richeyworks.superbeefsort.profile.ProfileDepth;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gap 5 — the co-optimization prior strength is derived from the <em>realized</em> sort run, not fixed at
 * {@link ProfileGuidedScorer#DEFAULT_PRIOR} (docs/adr-csrbt-integration-deepening.md). These pin the
 * derivation contract: a clean, cheap, exactly-measured run nudges the tree harder toward the favored
 * strategy; an expensive, generic, or only-sampled run nudges it more softly; the strength always stays
 * inside the {@code [MIN_PRIOR, MAX_PRIOR]} band that is centered on the historical fixed prior; and a
 * neutral / absent run signal reproduces that historical prior exactly (so the change is a refinement, not
 * a behaviour break). The favored strategy itself is unchanged — only the confidence placed in it moves
 * (the strategy mapping is covered by {@link CoOptimizationTest}).
 */
class ProfileGuidedPriorTest {

    private static final StrategyId ANY = StrategyId.of("test"); // strategy id is irrelevant to the derivation

    /** A SortResult carrying only the signal the derivation reads: size + comparison count. */
    private static SortResult metrics(int n, long comparisons) {
        return new SortResult(ANY, n, comparisons, 0L, 0L, 0L);
    }

    /** The {@code n·log2 n} comparison baseline the cleanliness facet measures against. */
    private static double baseline(int n) {
        return n * (Math.log(n) / Math.log(2.0));
    }

    /** A profile whose only derivation-relevant fields are the depth + the inversion measurement. */
    private static DataProfile profile(ProfileDepth depth, long inversions, boolean exact) {
        // 10-arg back-compat ctor: size, sortednessRatio, hasDuplicatesSampled, depth,
        //                          distinctEstimate, keyStats, distribution, longestRun, inversions, inversionsExact
        return new DataProfile(1000, 0.5, false, depth, 1000, null, Distribution.UNIFORM, 0, inversions, exact);
    }

    // ---- band + continuity --------------------------------------------------

    @Test
    void derivedPriorAlwaysStaysWithinTheBand() {
        long[] comparisons = {0, 1, 250, 5_000, 10_240, 100_000};
        int n = 1024;
        for (long c : comparisons) {
            for (ProfileDepth d : ProfileDepth.values()) {
                for (boolean exact : new boolean[]{true, false}) {
                    long inv = exact ? 100 : -1; // -1 == unmeasured; exact implies measured
                    double p = ProfileGuidedScorer.derivePrior(metrics(n, c), profile(d, inv, exact));
                    assertTrue(p >= ProfileGuidedScorer.MIN_PRIOR - 1e-12
                                    && p <= ProfileGuidedScorer.MAX_PRIOR + 1e-12,
                            "prior " + p + " escaped [" + ProfileGuidedScorer.MIN_PRIOR + ", "
                                    + ProfileGuidedScorer.MAX_PRIOR + "]");
                }
            }
        }
    }

    @Test
    void fullConfidenceHitsTheTopOfTheBand() {
        // cleanliness 1 (zero comparisons) + certainty 1 (exact, DEEP) -> confidence 1 -> MAX_PRIOR.
        double p = ProfileGuidedScorer.derivePrior(metrics(1024, 0), profile(ProfileDepth.DEEP, 100, true));
        assertEquals(ProfileGuidedScorer.MAX_PRIOR, p, 1e-9);
    }

    @Test
    void neutralOrAbsentRunReproducesTheHistoricalFixedPrior() {
        // The band is centered on DEFAULT_PRIOR, so a neutral confidence (0.5) lands exactly on it.
        assertEquals(ProfileGuidedScorer.DEFAULT_PRIOR,
                (ProfileGuidedScorer.MIN_PRIOR + ProfileGuidedScorer.MAX_PRIOR) / 2.0, 1e-12);

        // No metrics at all -> fall back to the historical fixed prior (callers without a run are unchanged).
        assertEquals(ProfileGuidedScorer.DEFAULT_PRIOR,
                ProfileGuidedScorer.derivePrior(null, profile(ProfileDepth.DEEP, 100, true)), 1e-12);

        // A genuinely neutral read (size too small to judge cleanliness; no profile to judge certainty)
        // -> confidence 0.5 -> exactly DEFAULT_PRIOR.
        assertEquals(ProfileGuidedScorer.DEFAULT_PRIOR,
                ProfileGuidedScorer.derivePrior(metrics(1, 0), null), 1e-12);
    }

    // ---- the two confidence facets move the prior the right way -------------

    @Test
    void cleanCheapSortNudgesHarderThanAGenericOne() {
        DataProfile p = profile(ProfileDepth.DEEP, 100, true); // hold certainty fixed
        int n = 1024;
        double cheap = ProfileGuidedScorer.derivePrior(metrics(n, (long) (0.05 * baseline(n))), p);
        double generic = ProfileGuidedScorer.derivePrior(metrics(n, (long) (2 * baseline(n))), p);
        assertTrue(cheap > generic,
                "a cheap, clean sort should yield a stronger prior: " + cheap + " vs " + generic);
        // once comparisons exceed the baseline the cleanliness facet saturates at 0, so any two
        // past-baseline runs yield the same prior (confidence then rests on certainty alone).
        double further = ProfileGuidedScorer.derivePrior(metrics(n, (long) (5 * baseline(n))), p);
        assertEquals(generic, further, 1e-12);
    }

    @Test
    void exactlyMeasuredProfileNudgesHarderThanSampledThanUnmeasured() {
        SortResult m = metrics(1024, 4000); // hold cleanliness fixed
        double exact = ProfileGuidedScorer.derivePrior(m, profile(ProfileDepth.DEEP, 100, true));
        double sampled = ProfileGuidedScorer.derivePrior(m, profile(ProfileDepth.SHALLOW, 100, false));
        double unmeasured = ProfileGuidedScorer.derivePrior(m, profile(ProfileDepth.SHALLOW, -1, false));
        assertTrue(exact > sampled, "exact measurement should beat a sample: " + exact + " vs " + sampled);
        assertTrue(sampled > unmeasured, "a sample should beat no measurement: " + sampled + " vs " + unmeasured);
    }

    @Test
    void confidenceIsAlwaysInTheUnitInterval() {
        int[] ns = {0, 1, 2, 16, 1024};
        long[] cs = {0, 1, 100, 100_000};
        for (int n : ns) {
            for (long c : cs) {
                for (ProfileDepth d : ProfileDepth.values()) {
                    double conf = ProfileGuidedScorer.confidenceFrom(metrics(n, c), profile(d, 50, false));
                    assertTrue(conf >= 0.0 && conf <= 1.0, "confidence escaped [0,1]: " + conf);
                }
            }
        }
        double nullProfileConf = ProfileGuidedScorer.confidenceFrom(metrics(1024, 0), null);
        assertTrue(nullProfileConf >= 0.0 && nullProfileConf <= 1.0);
    }

    // ---- forRun wires the derived prior in -----------------------------------

    @Test
    void forRunUsesTheDerivedPriorAndKeepsTheFavoredStrategy() {
        DataProfile p = profile(ProfileDepth.DEEP, 100, true);
        SortResult m = metrics(1024, 500);

        ProfileGuidedScorer scorer = ProfileGuidedScorer.forRun(p, AccessPolicy.READ_HEAVY, m);

        assertEquals(ProfileGuidedScorer.derivePrior(m, p), scorer.prior(), 1e-12);
        assertEquals(ProfileGuidedScorer.favoredStrategy(p, AccessPolicy.READ_HEAVY), scorer.favored(),
                "deriving the strength must not change which strategy is favored");
        // a derived prior is always a legal construction (the ctor rejects anything outside [0,1)).
        assertTrue(scorer.prior() >= 0.0 && scorer.prior() < 1.0);
    }
}
