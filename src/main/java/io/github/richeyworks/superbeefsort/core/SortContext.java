package io.github.richeyworks.superbeefsort.core;

import java.util.OptionalLong;

/**
 * Per-run context handed to a {@link SortStrategy}. Phase 0 strategies are pure and ignore it; it
 * exists so cancellation, step-event emission, and profile hints can be threaded through later
 * phases without changing the {@link SortStrategy} signature.
 *
 * <p>An optional {@code randomSeed} enables <b>deterministic mode</b>: a strategy that would otherwise
 * draw on a shared/thread-local RNG (e.g. {@code QuickSortStrategy}'s randomized pivot) seeds its PRNG
 * from it instead, so a run on a given input is exactly reproducible — useful for debugging, golden
 * tests, and stable benchmarks. Absent a seed, behaviour is unchanged.</p>
 */
public final class SortContext {

    private final SortObserver observer;
    private final OptionalLong randomSeed;
    private volatile boolean cancelled;

    public SortContext(SortObserver observer) {
        this(observer, OptionalLong.empty());
    }

    public SortContext(SortObserver observer, OptionalLong randomSeed) {
        this.observer = observer == null ? SortObserver.NOOP : observer;
        this.randomSeed = randomSeed == null ? OptionalLong.empty() : randomSeed;
    }

    public static SortContext noop() {
        return new SortContext(SortObserver.NOOP);
    }

    /** Deterministic mode with no observer: randomized strategies seed their PRNG from {@code seed}. */
    public static SortContext deterministic(long seed) {
        return new SortContext(SortObserver.NOOP, OptionalLong.of(seed));
    }

    public SortObserver observer() {
        return observer;
    }

    /** When present, randomized strategies must seed their PRNG from it for a reproducible run. */
    public OptionalLong randomSeed() {
        return randomSeed;
    }

    public boolean cancelled() {
        return cancelled;
    }

    public void cancel() {
        cancelled = true;
    }
}
