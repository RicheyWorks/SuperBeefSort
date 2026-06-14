package io.github.richeyworks.superbeefsort.core;

/**
 * A lifecycle event in a sort-and-feed job. Step-level events (per comparison / swap) are gated
 * behind a later phase so the throughput path pays nothing for observability.
 */
public record SortEvent(Type type, String detail) {

    public enum Type {
        JOB_STARTED,
        PROFILED,
        PLAN_SELECTED,
        SORT_COMPLETED,
        FEED_STARTED,
        FEED_COMPLETED,
        JOB_COMPLETED,
        ERROR
    }

    public static SortEvent of(Type type, String detail) {
        return new SortEvent(type, detail);
    }

    @Override
    public String toString() {
        return "[" + type + "] " + detail;
    }
}
