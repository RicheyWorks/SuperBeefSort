package io.github.richeyworks.superbeefsort.select;

import io.github.richeyworks.superbeefsort.core.SortResult;
import io.github.richeyworks.superbeefsort.core.StrategyId;
import io.github.richeyworks.superbeefsort.profile.DataProfile;

/**
 * Sink for one observed sort outcome — a single labeled training row for Phase 4
 * (docs/adr-phase4-python-intelligence.md, action item 1): the {@link DataProfile} features the selector saw,
 * the {@link StrategyId} that actually ran, and the measured {@link SortResult}.
 *
 * <p>Implementations must be cheap and thread-safe: a learning selector is typically shared across many jobs
 * and {@link ObservingStrategySelector#observe} may be called concurrently.</p>
 */
@FunctionalInterface
public interface ObservationSink {

    void record(DataProfile profile, StrategyId strategy, SortResult outcome);

    /** Discards every observation. */
    ObservationSink NOOP = (profile, strategy, outcome) -> { };
}
