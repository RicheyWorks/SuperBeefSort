package io.github.richeyworks.superbeefsort.core;

/**
 * A pluggable sorting algorithm — the core extension point of SuperBeefSort. Implementations sort
 * the {@link SortBuffer} in place and declare their {@link StrategyCapabilities} so the selector can
 * reason about them without hard-coding names.
 */
public interface SortStrategy<K> {

    /** Sort {@code buffer} in place. */
    void sort(SortBuffer<K> buffer, SortContext context);

    StrategyCapabilities capabilities();

    StrategyId id();
}
