package io.github.richeyworks.superbeefsort.csrbt;

import io.github.richeyworks.csrbt.control.StrategyId;

/**
 * A running summary of what CSRBT's <em>ensemble</em> control plane did after the sort+feed handoff — the
 * ensemble analog of {@link AdaptationReport} (docs/architecture-csrbt-integration.md §4). Accumulated by
 * {@link EnsembleAdaptation} from the {@code PromotionResult} / {@code HealthReport} streams the
 * {@link io.github.richeyworks.csrbt.ensemble.EnsembleController} returns, so it needs no extra CSRBT
 * surface.
 *
 * <p>Unlike single-set morphing (where zero morphs is the "born right" success), promotion is the
 * <em>point</em>: it migrates the read path to whichever member matches live traffic via an O(1) primary
 * swap (no rebuild — the win that pays for the mirror's write fan-out). {@link #failovers} counts the
 * health-driven swaps away from a member that failed its invariant check.</p>
 *
 * @param evaluations     number of {@code maybePromote()} cycles run since the adaptation was attached
 * @param promotions      how many of those cycles migrated the read path to a different member (O(1) swap)
 * @param failovers       how many {@code checkHealth()} passes failed the primary over to a healthy member
 * @param currentPrimary  the {@link StrategyId} of the member currently serving reads ({@code null} if unmapped)
 */
public record EnsembleAdaptationReport(int evaluations, int promotions, int failovers, StrategyId currentPrimary) {

    /** One-line summary suitable for logs or an observer. */
    public String summary() {
        return "ensemble: " + evaluations + " evals, " + promotions + " promotion(s), "
                + failovers + " failover(s), serving " + currentPrimary;
    }
}
