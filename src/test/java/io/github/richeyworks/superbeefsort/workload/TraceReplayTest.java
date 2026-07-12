package io.github.richeyworks.superbeefsort.workload;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.control.MorphPolicy;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.superbeefsort.csrbt.WorkloadAdaptation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Trace record → replay round-trip (Phase 3.3). A recorded op stream, replayed into a fresh set,
 * must reconstruct the exact same set; the trace file's line count must equal the op count; and
 * {@link Workloads#fromTrace} must surface the trace's aggregate mix and cyclic key stream.
 */
class TraceReplayTest {

    private static WorkloadAdaptation<Integer> freshSet() {
        return WorkloadAdaptation.attach(
                OrderedSet.withNaturalOrder(new RedBlackStrategy<Integer>()), MorphPolicy.defaults());
    }

    @Test
    void recordThenReplayReconstructsTheExactSet(@TempDir Path dir) throws IOException {
        Path trace = dir.resolve("session.trace");
        WorkloadAdaptation<Integer> recorded = freshSet();
        long opsWritten;
        try (TraceRecorder rec = new TraceRecorder(recorded, trace)) {
            Random rnd = new Random(123);
            for (int i = 0; i < 500; i++) {
                int key = rnd.nextInt(80);
                switch (rnd.nextInt(3)) {
                    case 0 -> rec.add(key);
                    case 1 -> rec.remove(key);
                    default -> rec.contains(key);
                }
            }
            opsWritten = rec.opsRecorded();
        }
        assertEquals(500, opsWritten);
        assertEquals(opsWritten, Files.readAllLines(trace).size(), "one trace line per op");

        WorkloadAdaptation<Integer> replayed = freshSet();
        long replayedOps = TraceReplayer.replay(trace, replayed, 0.0);    // speed<=0: as fast as possible
        assertEquals(500, replayedOps);
        assertEquals(recorded.set().inOrder(), replayed.set().inOrder(),
                "replaying the trace reconstructs the identical set");
    }

    @Test
    void pacedReplayAppliesEveryOp(@TempDir Path dir) throws IOException {
        // speed>0 exercises the pacing branch; the trace's t values are ~0 ms so no real delay.
        Path trace = dir.resolve("paced.trace");
        WorkloadAdaptation<Integer> recorded = freshSet();
        try (TraceRecorder rec = new TraceRecorder(recorded, trace)) {
            for (int i = 0; i < 50; i++) rec.add(i);
        }
        WorkloadAdaptation<Integer> replayed = freshSet();
        long ops = TraceReplayer.replay(trace, replayed, 1000.0);
        assertEquals(50, ops);
        assertEquals(recorded.set().inOrder(), replayed.set().inOrder());
    }

    @Test
    void fromTraceSurfacesMixAndCyclicKeyStream(@TempDir Path dir) throws IOException {
        Path trace = dir.resolve("mix.trace");
        WorkloadAdaptation<Integer> set = freshSet();
        try (TraceRecorder rec = new TraceRecorder(set, trace)) {
            rec.add(1);
            rec.add(2);
            rec.add(3);           // 3 adds
            rec.remove(2);        // 1 remove
            rec.contains(1);
            rec.contains(3);      // 2 contains  → keys in order: 1,2,3,2,1,3
        }
        Regime regime = Workloads.fromTrace(trace);
        assertEquals(6, regime.ops());
        assertEquals(2.0 / 6.0, regime.readFraction(), 1e-9, "contains / total");
        assertEquals(3.0 / 4.0, regime.addBias(), 1e-9, "adds / (adds+removes)");

        IntSupplier keys = regime.keys();
        int[] expected = {1, 2, 3, 2, 1, 3, 1, 2, 3};        // the 6 trace keys, then wrapping
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], keys.getAsInt(), "cyclic key at index " + i);
        }
    }
}
