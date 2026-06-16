package io.github.richeyworks.superbeefsort.stream;

import io.github.richeyworks.superbeefsort.core.SortResult;
import io.github.richeyworks.superbeefsort.feed.FeedResult;
import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.select.SortPlan;

/**
 * The outcome of one {@link AdaptiveStreamSorter#accept(java.util.List) batch}: its profile, the plan
 * in force (freshly selected iff {@code reselected}), the drift score/reason that drove the decision,
 * and the sort + feed metrics. A stream of these is the adaptive driver's audit trail — it shows when
 * and why the engine switched strategies as the data shifted.
 *
 * @param batchIndex  1-based position of this batch in the stream
 * @param profile     the (SHALLOW) profile measured for this batch
 * @param plan        the {@link SortPlan} used for this batch (the cached plan unless {@code reselected})
 * @param reselected  true when drift (or the first batch) forced a fresh strategy selection
 * @param driftScore  the detector's drift distance for this batch in {@code [0,1]}
 * @param driftReason the detector's human-readable explanation
 * @param sortMetrics comparisons / moves / time for this batch's sort
 * @param feedResult  the streaming-feed outcome for this batch
 */
public record StreamSortResult<K>(
        int batchIndex,
        DataProfile profile,
        SortPlan plan,
        boolean reselected,
        double driftScore,
        String driftReason,
        SortResult sortMetrics,
        FeedResult feedResult) {
}
