package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.profile.IntelligentDataProfiler;
import io.github.richeyworks.superbeefsort.profile.ProfileDepth;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The profiler's longest-run signal: length of the single longest ascending (non-decreasing) run. */
class LongestRunTest {

    private static DataProfile profile(List<Integer> data) {
        SortBuffer<Integer> b = SortBuffer.of(new ArrayList<>(data), Comparator.<Integer>naturalOrder());
        return new IntelligentDataProfiler<Integer>().profile(b, ProfileDepth.SHALLOW);
    }

    @Test
    void sortedInputIsOneLongRun() {
        assertEquals(8, profile(List.of(1, 2, 3, 4, 5, 6, 7, 8)).longestRun());
    }

    @Test
    void reversedInputHasRunsOfOne() {
        assertEquals(1, profile(List.of(8, 7, 6, 5, 4, 3, 2, 1)).longestRun());
    }

    @Test
    void picksTheLongestAscendingSegment() {
        // [1,2,3,9] (len 4) | drop to 4 | [4,5] (len 2) | drop to 0  ->  longest run is 4
        DataProfile p = profile(List.of(1, 2, 3, 9, 4, 5, 0));
        assertEquals(4, p.longestRun());
        assertEquals(4.0 / 7.0, p.longestRunRatio(), 1e-9);
    }

    @Test
    void equalKeysCountAsAscending() {
        assertEquals(3, profile(List.of(3, 3, 3)).longestRun(), "non-decreasing run includes ties");
    }

    @Test
    void backCompatConstructorDefaultsToZero() {
        DataProfile p = new DataProfile(10, 0.5, false, ProfileDepth.SHALLOW, 10, null,
                io.github.richeyworks.superbeefsort.profile.Distribution.UNKNOWN);
        assertEquals(0, p.longestRun(), "7-arg back-compat constructor leaves longestRun unmeasured");
    }
}
