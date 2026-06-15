package io.github.richeyworks.superbeefsort.select;

import io.github.richeyworks.superbeefsort.core.SortResult;
import io.github.richeyworks.superbeefsort.core.StrategyId;
import io.github.richeyworks.superbeefsort.profile.DataProfile;

/**
 * A {@link StrategySelector} that improves from observed outcomes. After each sort the engine reports
 * what actually happened back through {@link #observe}, letting the selector refine its future
 * choices; the "engine that tunes itself" angle.
 *
 * <p>Selectors that don't learn just implement {@link StrategySelector}; {@code BeefSortEngine} only
 * calls {@code observe} when its selector implements this sub-interface, so the feedback path is
 * entirely opt-in and the stateless selectors pay nothing for it.</p>
 */
public interface LearningStrategySelector extends StrategySelector {

    /**
     * Feedback for one completed sort. {@code outcome} carries the measured cost (comparisons, moves,
     * elapsed) for {@code strategy} run on data described by {@code profile}, the same profile that
     * was passed to the {@link #select} call that produced the plan (machine-measured, this run).
     *
     * <p>Implementations must be cheap, must tolerate being called for a strategy they did not
     * select (the engine can fall back when a chosen strategy cannot run), and should be safe to call
     * from multiple threads since a single learning selector is typically shared across many jobs.</p>
     */
    void observe(DataProfile profile, StrategyId strategy, SortResult outcome);
}
