package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.superbeefsort.core.SortResult;
import io.github.richeyworks.superbeefsort.core.StrategyId;
import io.github.richeyworks.superbeefsort.feed.FeedMode;
import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.profile.Distribution;
import io.github.richeyworks.superbeefsort.profile.KeyStats;
import io.github.richeyworks.superbeefsort.profile.ProfileDepth;
import io.github.richeyworks.superbeefsort.registry.StrategyRegistry;
import io.github.richeyworks.superbeefsort.select.CostModelStrategySelector;
import io.github.richeyworks.superbeefsort.select.LearningStrategySelector;
import io.github.richeyworks.superbeefsort.select.ObservationSink;
import io.github.richeyworks.superbeefsort.select.ObservingStrategySelector;
import io.github.richeyworks.superbeefsort.select.SelectionPolicy;
import io.github.richeyworks.superbeefsort.select.SortPlan;
import io.github.richeyworks.superbeefsort.select.StrategySelector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4 action item 1 (docs/adr-phase4-python-intelligence.md): {@link ObservingStrategySelector} taps the
 * engine's {@code observe(...)} stream into a labeled training corpus without changing which strategy is
 * selected. Pins: selection is the delegate's verbatim; every observation is recorded; a learning delegate
 * still learns; and the CSV schema (header/row, null key-stats) is stable.
 */
class ObservingStrategySelectorTest {

    private final StrategyRegistry registry = StrategyRegistry.withDefaults();

    private static DataProfile intProfile() {
        // 10-arg ctor -> hasByteSequenceKey = false
        return new DataProfile(1000, 0.5, false, ProfileDepth.SHALLOW, 1000,
                new KeyStats(0, 999, true), Distribution.UNIFORM, 10, 250L, true);
    }

    private static DataProfile noKeyProfile() {
        return new DataProfile(64, 0.9, true, ProfileDepth.DEEP, 60, null, Distribution.UNKNOWN);
    }

    private static SortResult outcome(String id) {
        return new SortResult(StrategyId.of(id), 1000, 1234L, 567L, 89000L, 16000L);
    }

    @Test
    void selectIsTheDelegatesVerbatim() {
        StrategySelector delegate = new CostModelStrategySelector();
        ObservingStrategySelector obs = new ObservingStrategySelector(delegate, ObservationSink.NOOP);
        DataProfile p = intProfile();
        assertEquals(delegate.select(p, SelectionPolicy.SMART, registry).strategy().value(),
                obs.select(p, SelectionPolicy.SMART, registry).strategy().value());
    }

    @Test
    void observeRecordsExactlyTheExpectedRow() {
        List<String> rows = new ArrayList<>();
        ObservingStrategySelector obs = new ObservingStrategySelector(
                new CostModelStrategySelector(),
                (p, s, o) -> rows.add(ObservingStrategySelector.csvRow(p, s, o)));

        obs.observe(intProfile(), StrategyId.of("radix.lsd"), outcome("radix.lsd"));

        assertEquals(1, rows.size());
        assertEquals(ObservingStrategySelector.csvHeader().split(",", -1).length,
                rows.get(0).split(",", -1).length, "row column count matches the header");
        assertEquals(
                "1000,0.5,false,SHALLOW,1000,0,999,999,true,UNIFORM,10,250,true,false,"
                        + "radix.lsd,1234,567,89000,16000",
                rows.get(0));
    }

    @Test
    void csvRendersNullKeyStatsAsEmptyCells() {
        List<String> rows = new ArrayList<>();
        ObservingStrategySelector obs = new ObservingStrategySelector(
                new CostModelStrategySelector(),
                (p, s, o) -> rows.add(ObservingStrategySelector.csvRow(p, s, o)));

        obs.observe(noKeyProfile(), StrategyId.of("intro"), outcome("intro"));

        String row = rows.get(0);
        assertEquals(ObservingStrategySelector.csvHeader().split(",", -1).length, row.split(",", -1).length);
        assertTrue(row.contains(",,,,"), "null keyStats -> empty keyMin/keyMax/keySpan/countingFeasible cells");
    }

    @Test
    void observeForwardsToALearningDelegate() {
        List<StrategyId> seenByDelegate = new ArrayList<>();
        LearningStrategySelector learningDelegate = new LearningStrategySelector() {
            @Override
            public SortPlan select(DataProfile p, SelectionPolicy pol, StrategyRegistry r) {
                return new SortPlan(StrategyId.of("intro"), FeedMode.BULK, StrategyId.of("intro"), "stub");
            }
            @Override
            public void observe(DataProfile p, StrategyId s, SortResult o) {
                seenByDelegate.add(s);
            }
        };
        ObservingStrategySelector obs = new ObservingStrategySelector(learningDelegate, ObservationSink.NOOP);

        obs.observe(intProfile(), StrategyId.of("counting"), outcome("counting"));

        assertEquals(1, seenByDelegate.size(), "the wrapped learning selector still receives the observation");
        assertEquals("counting", seenByDelegate.get(0).value());
    }

    @Test
    void csvSinkWritesHeaderOnceThenOneRowPerObservation() {
        StringBuilder out = new StringBuilder();
        ObservationSink sink = ObservingStrategySelector.csvSink(out);

        sink.record(intProfile(), StrategyId.of("radix.lsd"), outcome("radix.lsd"));
        sink.record(noKeyProfile(), StrategyId.of("intro"), outcome("intro"));

        String[] lines = out.toString().split("\n");
        assertEquals(3, lines.length, "header + 2 rows");
        assertEquals(ObservingStrategySelector.csvHeader(), lines[0]);
        assertTrue(lines[1].startsWith("1000,0.5,false,SHALLOW,"));
        assertTrue(lines[2].startsWith("64,0.9,true,DEEP,"));
    }
}
