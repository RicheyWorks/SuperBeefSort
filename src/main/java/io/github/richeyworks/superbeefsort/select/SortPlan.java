package io.github.richeyworks.superbeefsort.select;

import io.github.richeyworks.superbeefsort.core.StrategyId;
import io.github.richeyworks.superbeefsort.feed.FeedMode;

/**
 * The decision the selector makes: which strategy to run, how to feed CSRBT, and a guaranteed
 * fallback strategy if the first choice cannot run. The {@code rationale} is surfaced as an event.
 */
public record SortPlan(StrategyId strategy, FeedMode feedMode, StrategyId fallback, String rationale) {
}
