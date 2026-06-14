package io.github.richeyworks.superbeefsort.core;

/**
 * Per-run context handed to a {@link SortStrategy}. Phase 0 strategies are pure and ignore it; it
 * exists so cancellation, step-event emission, and profile hints can be threaded through later
 * phases without changing the {@link SortStrategy} signature.
 */
public final class SortContext {

    private final SortObserver observer;
    private volatile boolean cancelled;

    public SortContext(SortObserver observer) {
        this.observer = observer == null ? SortObserver.NOOP : observer;
    }

    public static SortContext noop() {
        return new SortContext(SortObserver.NOOP);
    }

    public SortObserver observer() {
        return observer;
    }

    public boolean cancelled() {
        return cancelled;
    }

    public void cancel() {
        cancelled = true;
    }
}
