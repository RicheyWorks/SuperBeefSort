package io.github.richeyworks.superbeefsort.engine;

import io.github.richeyworks.superbeefsort.core.SortObserver;
import io.github.richeyworks.superbeefsort.feed.FeedMode;
import io.github.richeyworks.superbeefsort.select.SelectionPolicy;

/** Immutable tunables for a sort-and-feed job. Use the {@code with*} methods to derive variants. */
public final class JobSpec {

    private final SelectionPolicy policy;
    private final FeedMode feedModeOverride; // null -> use the plan's choice
    private final SortObserver observer;

    private JobSpec(SelectionPolicy policy, FeedMode feedModeOverride, SortObserver observer) {
        this.policy = policy;
        this.feedModeOverride = feedModeOverride;
        this.observer = observer == null ? SortObserver.NOOP : observer;
    }

    public static JobSpec defaults() {
        return new JobSpec(SelectionPolicy.SMART, null, SortObserver.NOOP);
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

    public JobSpec withPolicy(SelectionPolicy p) {
        return new JobSpec(p, feedModeOverride, observer);
    }

    public JobSpec withFeedMode(FeedMode m) {
        return new JobSpec(policy, m, observer);
    }

    public JobSpec withObserver(SortObserver o) {
        return new JobSpec(policy, feedModeOverride, o);
    }
}
