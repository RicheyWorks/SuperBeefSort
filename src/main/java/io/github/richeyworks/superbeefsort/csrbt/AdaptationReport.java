package io.github.richeyworks.superbeefsort.csrbt;

import io.github.richeyworks.csrbt.control.StrategyId;

/**
 * A running summary of what CSRBT's control plane did <em>after</em> the sort+feed handoff — the
 * post-feed observability the integration design calls for (docs/architecture-csrbt-integration.md §5,
 * rollout item 5). Accumulated by {@link WorkloadAdaptation} from the {@link
 * io.github.richeyworks.csrbt.control.MorphController.MorphResult} stream its {@code maybeAdapt()} already
 * returns, so it needs no extra CSRBT surface.
 *
 * <p>The headline field is {@link #morphs}: the §5 success metric is that a tree <em>born</em> in the
 * profile/access-advised strategy (see {@link StrategyAdvisor}) <em>HOLDs</em> — it was shaped right at
 * construction, so the control plane never has to morph it on a matching workload. {@link #held()} is that
 * assertion in one call, and the {@code BornRightHolds} guardrail test pins it.</p>
 *
 * @param evaluations    number of {@code maybeAdapt()} cycles run since the adaptation was attached
 * @param morphs         how many of those cycles actually morphed the tree's strategy
 * @param currentStrategy the live morph-family strategy the set currently holds
 */
public record AdaptationReport(int evaluations, int morphs, StrategyId currentStrategy) {

    /** True iff the tree never morphed — "born right" held under the live workload (the §5 success metric). */
    public boolean held() {
        return morphs == 0;
    }

    /** One-line summary suitable for logs or an observer. */
    public String summary() {
        return "adaptation: " + evaluations + " evals, " + morphs + " morph(s), now "
                + currentStrategy + (held() ? "  [HELD]" : "");
    }
}
