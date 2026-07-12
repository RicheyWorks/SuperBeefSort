package io.github.richeyworks.superbeefsort.workload;

import io.github.richeyworks.superbeefsort.csrbt.WorkloadAdaptation;
import io.github.richeyworks.superbeefsort.source.MiniJson;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Replays a trace written by {@link TraceRecorder} into a {@link WorkloadAdaptation}{@code <Integer>},
 * reproducing the recorded {@code add}/{@code remove}/{@code contains} sequence. Lines are parsed
 * with the same minimal top-level scanner the JSONL source uses ({@link MiniJson}); a blank line is
 * skipped and a line missing {@code op} or {@code key} is a hard error.
 *
 * <p><b>Speed:</b> {@code speed <= 0} replays as fast as possible (the deterministic test mode —
 * timestamps are ignored); {@code speed > 0} honours the recorded inter-op gaps scaled by
 * {@code speed} ({@code 1.0} = real time, {@code 2.0} = twice as fast) by sleeping between ops.
 * A thread interrupt during a paced sleep stops the replay cleanly (interrupt flag restored).
 */
public final class TraceReplayer {

    private TraceReplayer() {
    }

    /**
     * Replay {@code path} into {@code target}. Returns the number of ops applied (= the trace's
     * non-blank line count on a clean run).
     */
    public static long replay(Path path, WorkloadAdaptation<Integer> target, double speed)
            throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(target, "target");
        long ops = 0;
        long firstT = Long.MIN_VALUE;
        long wallStartNanos = System.nanoTime();
        long lineNumber = 0;
        try (BufferedReader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                String op = MiniJson.field(line, "op");
                String keyRaw = MiniJson.field(line, "key");
                if (op == null || keyRaw == null) {
                    throw new IOException("trace line " + lineNumber
                            + " missing 'op' or 'key': " + line);
                }
                int key = Integer.parseInt(keyRaw.trim());

                if (speed > 0) {
                    String tRaw = MiniJson.field(line, "t");
                    long t = (tRaw == null) ? 0 : Long.parseLong(tRaw.trim());
                    if (firstT == Long.MIN_VALUE) {
                        firstT = t;
                    }
                    long targetElapsedMs = (long) ((t - firstT) / speed);
                    long actualElapsedMs = (System.nanoTime() - wallStartNanos) / 1_000_000L;
                    long sleepMs = targetElapsedMs - actualElapsedMs;
                    if (sleepMs > 0) {
                        try {
                            Thread.sleep(sleepMs);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return ops;                 // stop cleanly, flag restored
                        }
                    }
                }

                switch (op) {
                    case "add":      target.add(key);      break;
                    case "remove":   target.remove(key);   break;
                    case "contains": target.contains(key); break;
                    default:
                        throw new IOException("trace line " + lineNumber
                                + " has unknown op '" + op + "': " + line);
                }
                ops++;
            }
        }
        return ops;
    }
}
