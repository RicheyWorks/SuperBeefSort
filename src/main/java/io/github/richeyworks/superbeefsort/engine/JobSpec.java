package io.github.richeyworks.superbeefsort.engine;

import io.github.richeyworks.superbeefsort.core.SortObserver;
import io.github.richeyworks.superbeefsort.core.StepEventSink;
import io.github.richeyworks.superbeefsort.feed.FeedMode;
import io.github.richeyworks.superbeefsort.select.SelectionPolicy;

import java.util.OptionalLong;

/** Immutable tunables for a sort-and-feed job. Use the {@code with*} methods to derive variants. */
public final class JobSpec {

    private final SelectionPolicy policy;
    private final FeedMode feedModeOverride; // null -> use the plan's choice
    private final SortObserver observer;
    private final OptionalLong randomSeed;   // present -> deterministic mode for randomized strategies
    private final StepEventSink stepEventSink; // null -> step events disabled (default)

    private JobSpec(SelectionPolicy policy, FeedMode feedModeOverride, SortObserver observer,
                    OptionalLong randomSeed, StepEventSink stepEventSink) {
        this.policy = policy;
        this.feedModeOverride = feedModeOverride;
        this.observer = observer == null ? SortObserver.NOOP : observer;
        this.randomSeed = randomSeed == null ? OptionalLong.empty() : randomSeed;
        this.stepEventSink = stepEventSink;
    }

    public static JobSpec defaults() {
        return new JobSpec(SelectionPolicy.SMART, null, SortObserver.NOOP, OptionalLong.empty(), null);
    }

    public SelectionPolicy policy() {
        return policy;
    }

    public FeedMode feedModeOverride() {
        return feedModeOverride;
    }

    public SortObserver observer() {
        return observer;
    }

    /** When present, the engine puts randomized strategies into deterministic mode with this seed. */
    public OptionalLong randomSeed() {
        return randomSeed;
    }

    /**
     * The step-event sink, or {@code null} when step-event recording is disabled (the default).
     * Callers must guard with a null-check before enabling step events on the buffer.
     */
    public StepEventSink stepEventSink() {
        return stepEventSink;
    }

    public JobSpec withPolicy(SelectionPolicy p) {
        return new JobSpec(p, feedModeOverride, observer, randomSeed, stepEventSink);
    }

    public JobSpec withFeedMode(FeedMode m) {
        return new JobSpec(policy, m, observer, randomSeed, stepEventSink);
    }

    public JobSpec withObserver(SortObserver o) {
        return new JobSpec(policy, feedModeOverride, o, randomSeed, stepEventSink);
    }

    public JobSpec withRandomSeed(long seed) {
        return new JobSpec(policy, feedModeOverride, observer, OptionalLong.of(seed), stepEventSink);
    }

    /** Enable per-step event emission for the sort; the buffer emits nothing when this is not set. */
    public JobSpec withStepEvents(StepEventSink sink) {
        return new JobSpec(policy, feedModeOverride, observer, randomSeed, sink);
    }
}
