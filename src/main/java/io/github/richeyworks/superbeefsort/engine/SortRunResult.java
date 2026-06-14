package io.github.richeyworks.superbeefsort.engine;

import io.github.richeyworks.superbeefsort.core.SortResult;
import io.github.richeyworks.superbeefsort.feed.FeedResult;
import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.select.SortPlan;

import java.util.List;

/** Everything that happened in a job: the profile, the chosen plan, the sorted output, and metrics. */
public record SortRunResult<K>(
        DataProfile profile,
        SortPlan plan,
        List<K> sorted,
        SortResult sortMetrics,
        FeedResult feedResult) { // feedResult is null for sort-only jobs

    public boolean wasFed() {
        return feedResult != null;
    }
}
