package io.github.richeyworks.superbeefsort.select;

/** How the engine should choose a strategy. */
public enum SelectionPolicy {
    /** Always use the robust general-purpose strategy (introsort). */
    FIXED_INTRO,
    /** Profile the data and pick accordingly. */
    SMART,
    /** Favor a stable sort. */
    STABLE
}
