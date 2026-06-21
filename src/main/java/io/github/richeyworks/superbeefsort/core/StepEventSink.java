package io.github.richeyworks.superbeefsort.core;

/**
 * Receives per-step {@link StepEvent}s emitted during a sort. Register one on a
 * {@link SortBuffer} via {@link SortBuffer#enableStepEvents} before calling the strategy.
 * When no sink is registered the buffer emits nothing — zero extra cost on the hot path.
 */
@FunctionalInterface
public interface StepEventSink {

    void onStep(StepEvent event);

    /** Discards every step event; safe no-op sentinel. */
    StepEventSink NOOP = e -> {};
}
