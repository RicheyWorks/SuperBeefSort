package io.github.richeyworks.superbeefsort.core;

/** Sink for lifecycle {@link SortEvent}s emitted across the pipeline. */
@FunctionalInterface
public interface SortObserver {

    void onEvent(SortEvent event);

    /** Discards every event. */
    SortObserver NOOP = event -> { };
}
