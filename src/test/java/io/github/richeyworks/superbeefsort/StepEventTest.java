package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.StepEvent;
import io.github.richeyworks.superbeefsort.engine.JobSpec;
import io.github.richeyworks.superbeefsort.engine.SortRunResult;
import io.github.richeyworks.superbeefsort.strategy.HeapSortStrategy;
import io.github.richeyworks.superbeefsort.strategy.InsertionSortStrategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the step-event schema: flag-OFF costs nothing, flag-ON captures the correct stream.
 */
class StepEventTest {

    // ---- schema version ----

    @Test
    void schemaVersionIsOne() {
        assertEquals(1, StepEvent.SCHEMA_VERSION);
        assertEquals(1, new StepEvent.Comparison(0, 1, -1).schemaVersion());
        assertEquals(1, new StepEvent.Swap(0, 1).schemaVersion());
        assertEquals(1, new StepEvent.Move(0, 1).schemaVersion());
    }

    // ---- off-path: flag must never trigger sink ----

    @Test
    void flagOffEmitsZeroStepEvents() {
        // Default buffer has step events disabled — the sink must never be called.
        List<Integer> data = new ArrayList<>(List.of(5, 3, 1, 4, 2));
        AtomicInteger callCount = new AtomicInteger();

        SortBuffer<Integer> buffer = SortBuffer.of(data, Comparator.naturalOrder());
        // enableStepEvents is NOT called
        new HeapSortStrategy<Integer>().sort(buffer, SortContext.noop());

        assertEquals(0, callCount.get(), "step sink must never fire when flag is off");
        assertTrue(buffer.comparisons() > 0, "sort must have run");
    }

    @Test
    void flagOffViaJobSpecHasNullStepSink() {
        assertNull(JobSpec.defaults().stepEventSink(),
                "default JobSpec must carry null step sink (step events disabled)");
    }

    @Test
    void flagOffInsertionSortEmitsZeroEvents() {
        List<Integer> data = new ArrayList<>(List.of(4, 3, 2, 1));
        AtomicInteger callCount = new AtomicInteger();

        SortBuffer<Integer> buffer = SortBuffer.of(data, Comparator.naturalOrder());
        // NOT calling enableStepEvents
        new InsertionSortStrategy<Integer>().sort(buffer, SortContext.noop());

        assertEquals(0, callCount.get(), "no step events when flag is off");
        assertTrue(buffer.comparisons() > 0);
        assertTrue(buffer.moves() > 0);
    }

    // ---- on-path: HeapSort — pure compare(i,j) + swap(i,j) ----

    @Test
    void flagOnHeapSortComparisonCountMatchesBufferCounter() {
        // HeapSort only calls compare(i,j) — every comparison must produce a Comparison event.
        List<Integer> data = new ArrayList<>(List.of(5, 1, 3, 2, 4));
        List<StepEvent> events = new ArrayList<>();

        SortBuffer<Integer> buffer = SortBuffer.of(data, Comparator.naturalOrder());
        buffer.enableStepEvents(events::add);
        new HeapSortStrategy<Integer>().sort(buffer, SortContext.noop());

        long comparisonsEmitted = events.stream()
                .filter(e -> e instanceof StepEvent.Comparison).count();
        assertEquals(buffer.comparisons(), comparisonsEmitted,
                "every compare(i,j) call must produce a Comparison event");
    }

    @Test
    void flagOnHeapSortSwapCountMatchesBufferCounter() {
        List<Integer> data = new ArrayList<>(List.of(5, 1, 3, 2, 4));
        List<StepEvent> events = new ArrayList<>();

        SortBuffer<Integer> buffer = SortBuffer.of(data, Comparator.naturalOrder());
        buffer.enableStepEvents(events::add);
        new HeapSortStrategy<Integer>().sort(buffer, SortContext.noop());

        long swapsEmitted = events.stream()
                .filter(e -> e instanceof StepEvent.Swap).count();
        // HeapSort only uses swap(i,j) — moves() == 2 * swaps
        assertEquals(buffer.moves() / 2, swapsEmitted,
                "every swap(i,j) call must produce a Swap event");
    }

    @Test
    void flagOnEventsHaveValidBufferIndices() {
        List<Integer> data = new ArrayList<>(List.of(4, 2, 7, 1, 9, 3, 6, 5, 8));
        int n = data.size();
        List<StepEvent> events = new ArrayList<>();

        SortBuffer<Integer> buffer = SortBuffer.of(data, Comparator.naturalOrder());
        buffer.enableStepEvents(events::add);
        new HeapSortStrategy<Integer>().sort(buffer, SortContext.noop());

        assertFalse(events.isEmpty(), "at least one event must be emitted");

        for (StepEvent e : events) {
            if (e instanceof StepEvent.Comparison c) {
                assertTrue(c.i() >= 0 && c.i() < n,
                        "Comparison.i=" + c.i() + " out of [0," + n + ")");
                assertTrue((c.j() >= 0 && c.j() < n) || c.j() == -1,
                        "Comparison.j=" + c.j() + " invalid");
            } else if (e instanceof StepEvent.Swap s) {
                assertTrue(s.i() >= 0 && s.i() < n,
                        "Swap.i=" + s.i() + " out of [0," + n + ")");
                assertTrue(s.j() >= 0 && s.j() < n,
                        "Swap.j=" + s.j() + " out of [0," + n + ")");
            } else if (e instanceof StepEvent.Move m) {
                assertTrue(m.from() >= 0 && m.from() < n,
                        "Move.from=" + m.from() + " out of [0," + n + ")");
                assertTrue(m.to() >= 0 && m.to() < n,
                        "Move.to=" + m.to() + " out of [0," + n + ")");
            }
        }
    }

    // ---- on-path: InsertionSort — compareToKey + indexed recordMove ----

    @Test
    void flagOnInsertionSortCompareToKeyEmitsComparisonWithMinusOneJ() {
        // InsertionSort compares via compareToKey(j, key) → Comparison(j, -1, result)
        List<Integer> data = new ArrayList<>(List.of(3, 1, 2));
        List<StepEvent> events = new ArrayList<>();

        SortBuffer<Integer> buffer = SortBuffer.of(data, Comparator.naturalOrder());
        buffer.enableStepEvents(events::add);
        new InsertionSortStrategy<Integer>().sort(buffer, SortContext.noop());

        long comparisons = events.stream()
                .filter(e -> e instanceof StepEvent.Comparison).count();
        assertEquals(buffer.comparisons(), comparisons,
                "all compareToKey calls must produce Comparison events");

        // All insertion-sort comparisons are against an external key (j == -1)
        events.stream()
              .filter(e -> e instanceof StepEvent.Comparison)
              .map(e -> (StepEvent.Comparison) e)
              .forEach(c -> assertEquals(-1, c.j(),
                      "insertion-sort Comparison must have j=-1 (external key)"));
    }

    @Test
    void flagOnInsertionSortMoveEventsMatchBufferMoveCounter() {
        // InsertionSort upgraded to recordMove(from, to) — Move events must equal buffer.moves()
        // [3, 1, 2]: i=1 key=1: shift 3→slot1 (1 move); i=2 key=2: shift 3→slot2 (1 move) → 2 total
        List<Integer> data = new ArrayList<>(List.of(3, 1, 2));
        List<StepEvent> events = new ArrayList<>();

        SortBuffer<Integer> buffer = SortBuffer.of(data, Comparator.naturalOrder());
        buffer.enableStepEvents(events::add);
        new InsertionSortStrategy<Integer>().sort(buffer, SortContext.noop());

        long moveEvents = events.stream()
                .filter(e -> e instanceof StepEvent.Move).count();
        assertEquals(buffer.moves(), moveEvents,
                "each indexed recordMove call must emit a Move event");
        assertEquals(2, moveEvents, "exactly 2 shifts for [3,1,2]");
    }

    @Test
    void flagOnMoveEventIndicesAreCorrectForKnownInput() {
        // [3, 1, 2] with insertion sort:
        // i=1, key=1: compare(slot0=3, key=1)→pos, shift slot0→slot1 → Move(from=0, to=1)
        // i=2, key=2: compare(slot1=3, key=2)→pos, shift slot1→slot2 → Move(from=1, to=2)
        //             compare(slot0=1, key=2)→neg, stop
        List<Integer> data = new ArrayList<>(List.of(3, 1, 2));
        List<StepEvent.Move> moves = new ArrayList<>();

        SortBuffer<Integer> buffer = SortBuffer.of(data, Comparator.naturalOrder());
        buffer.enableStepEvents(e -> {
            if (e instanceof StepEvent.Move m) moves.add(m);
        });
        new InsertionSortStrategy<Integer>().sort(buffer, SortContext.noop());

        assertEquals(2, moves.size());
        assertEquals(new StepEvent.Move(0, 1), moves.get(0), "first shift: slot0→slot1");
        assertEquals(new StepEvent.Move(1, 2), moves.get(1), "second shift: slot1→slot2");
    }

    // ---- facade wiring ----

    @Test
    void beefSortFacadeStepEventsWiredThroughEngine() {
        List<Integer> data = new ArrayList<>(List.of(4, 2, 5, 1, 3));
        List<StepEvent> events = new ArrayList<>();

        SortRunResult<Integer> result = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(data)
                .stepEvents(events::add)
                .run();

        assertEquals(List.of(1, 2, 3, 4, 5), result.sorted(), "sort must still be correct");
        assertFalse(events.isEmpty(), "engine must propagate step events from BeefSort facade");
    }
}
