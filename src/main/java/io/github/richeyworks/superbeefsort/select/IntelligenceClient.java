package io.github.richeyworks.superbeefsort.select;

import io.github.richeyworks.superbeefsort.core.SortResult;
import io.github.richeyworks.superbeefsort.core.StrategyId;
import io.github.richeyworks.superbeefsort.profile.DataProfile;

import java.util.Optional;

/**
 * The seam between {@link RemoteStrategySelector} and the SbsIntelligence transport (Phase 4b, ADR
 * docs/adr-phase4-python-intelligence.md action item 6). Deliberately grpc-free so the selector and the
 * engine carry no network dependency — the gRPC implementation lives in the optional
 * {@code sbs-intelligence-client} module.
 *
 * <p>Every method is <b>total</b>: implementations MUST NOT throw and MUST NOT block past their own
 * deadline. A transport failure, a timeout, an open circuit breaker, or a genuine "no advice" all surface
 * the same way — {@link Optional#empty()} from {@link #predict} — so the caller simply keeps its local
 * delegate. This is the engine's standard fallback-first discipline.</p>
 */
public interface IntelligenceClient {

    /** One advisory verdict: the predicted strategy id, leaf confidence in {@code [0,1]}, and model version. */
    record Advice(String strategyId, double confidence, String modelVersion) {
    }

    /**
     * Ask the service for a strategy under {@code policy}. Returns {@link Optional#empty()} for any reason the
     * caller should ignore the service (no advice, timeout, error, breaker open). Never throws.
     */
    Optional<Advice> predict(DataProfile profile, SelectionPolicy policy);

    /**
     * Report a completed sort for continual learning. Best-effort, fire-and-forget: never throws, never blocks
     * the engine beyond its own deadline.
     */
    void observe(DataProfile profile, StrategyId chosen, SortResult outcome);

    /**
     * A no-op client — always {@link Optional#empty()}, ignores observations. Lets a
     * {@link RemoteStrategySelector} run purely on its local delegate (e.g. when no service is configured).
     */
    static IntelligenceClient disabled() {
        return new IntelligenceClient() {
            @Override
            public Optional<Advice> predict(DataProfile profile, SelectionPolicy policy) {
                return Optional.empty();
            }

            @Override
            public void observe(DataProfile profile, StrategyId chosen, SortResult outcome) {
                // intentionally ignored
            }
        };
    }
}
