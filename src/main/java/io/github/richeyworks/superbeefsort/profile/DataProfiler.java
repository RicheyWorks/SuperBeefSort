package io.github.richeyworks.superbeefsort.profile;

import io.github.richeyworks.superbeefsort.core.SortBuffer;

/** Inspects an input and reports its {@link DataProfile} — the engine's "inspection station". */
public interface DataProfiler<K> {

    DataProfile profile(SortBuffer<K> buffer, ProfileDepth depth);
}
