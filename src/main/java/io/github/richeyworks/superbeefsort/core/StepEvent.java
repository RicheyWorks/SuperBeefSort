package io.github.richeyworks.superbeefsort.core;

/**
 * Typed, versioned event for a single algorithm step (comparison, swap, or element move).
 * Emitted from {@link SortBuffer} only when step-event recording is explicitly enabled —
 * the default path is allocation-free and has no extra work.
 *
 * <p>{@link #SCHEMA_VERSION} must be bumped whenever any subtype's field shape changes
 * so downstream consumers (e.g. the TypeScript visualiser) can detect format changes
 * before attempting to decode events.</p>
 */
public sealed interface StepEvent permits StepEvent.Comparison, StepEvent.Swap, StepEvent.Move {

    /** Increment when the shape of any subtype changes. Readable on instances via {@link #schemaVersion()}. */
    int SCHEMA_VERSION = 1;

    /** Returns {@link #SCHEMA_VERSION}; queryable on any event without a cast. */
    default int schemaVersion() {
        return SCHEMA_VERSION;
    }

    /**
     * A comparison between two elements.
     *
     * @param i      left buffer index (always ≥ 0)
     * @param j      right buffer index, or {@code -1} when the right operand is an external
     *               key / pivot not currently in a buffer slot
     *               (emitted by {@link SortBuffer#compareToKey})
     * @param result sign of {@code comparator.compare(a[i], a[j/key])}: negative → left smaller,
     *               0 → equal, positive → left larger
     */
    record Comparison(int i, int j, int result) implements StepEvent {}

    /**
     * Elements at buffer indices {@code i} and {@code j} were exchanged
     * ({@link SortBuffer#swap}).
     */
    record Swap(int i, int j) implements StepEvent {}

    /**
     * An element was shifted or copied from one buffer slot to another. Emitted only when the
     * caller supplies indices via {@link SortBuffer#recordMove(int, int)}; the zero-argument
     * {@link SortBuffer#recordMove()} variant does <em>not</em> emit this event because it
     * carries no index information.
     */
    record Move(int from, int to) implements StepEvent {}
}
