package io.github.richeyworks.superbeefsort.workload;

import io.github.richeyworks.superbeefsort.csrbt.WorkloadAdaptation;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Records a live CSRBT workload to a replayable trace while it happens. Wraps a
 * {@link WorkloadAdaptation}{@code <Integer>} with the same {@code add}/{@code remove}/{@code contains}
 * facade: each call delegates to the wrapped adaptation (so the real tree adapts exactly as it
 * would unwrapped) <em>and</em> appends one JSONL op line
 * {@code {"op":"add|remove|contains","key":<int>,"t":<millisSinceStart>}}. The resulting file is a
 * committable, reproducible access pattern — feed it back through {@link TraceReplayer} or
 * {@link Workloads#fromTrace(Path)}.
 *
 * <p><b>Not thread-safe:</b> a single writer thread is assumed (the CSRBT contract anyway).
 * {@code t} is wall-clock millis since construction, so the trace preserves inter-op timing for a
 * speed-scaled replay; {@link TraceReplayer} at {@code speed <= 0} ignores it for deterministic
 * tests. {@link #close()} flushes and closes the file (idempotent); it does <em>not</em> close the
 * wrapped adaptation — the caller owns the set's lifecycle.
 */
public final class TraceRecorder implements Closeable {

    private final WorkloadAdaptation<Integer> delegate;
    private final BufferedWriter out;
    private final long startMillis;
    private long ops;
    private boolean closed;

    /** Begin recording {@code delegate}'s traffic to {@code path} (UTF-8, overwriting any existing file). */
    public TraceRecorder(WorkloadAdaptation<Integer> delegate, Path path) throws IOException {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(path, "path");
        this.out = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        this.startMillis = System.currentTimeMillis();
    }

    /** Insert {@code key} via the wrapped set and log the op. */
    public boolean add(int key) throws IOException {
        boolean result = delegate.add(key);
        writeOp("add", key);
        return result;
    }

    /** Remove {@code key} via the wrapped set and log the op. */
    public boolean remove(int key) throws IOException {
        boolean result = delegate.remove(key);
        writeOp("remove", key);
        return result;
    }

    /** Membership test of {@code key} via the wrapped set and log the op. */
    public boolean contains(int key) throws IOException {
        boolean result = delegate.contains(key);
        writeOp("contains", key);
        return result;
    }

    /** The wrapped adaptation, for periodic {@code maybeAdapt()} calls and inspection. */
    public WorkloadAdaptation<Integer> adaptation() {
        return delegate;
    }

    /** Number of ops recorded so far — equals the line count written to the trace. */
    public long opsRecorded() {
        return ops;
    }

    private void writeOp(String op, int key) throws IOException {
        long t = System.currentTimeMillis() - startMillis;
        // Fixed op vocabulary + integer key + integer t: no JSON escaping is reachable here.
        out.write("{\"op\":\"");
        out.write(op);
        out.write("\",\"key\":");
        out.write(Integer.toString(key));
        out.write(",\"t\":");
        out.write(Long.toString(t));
        out.write("}");
        out.newLine();
        ops++;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        out.flush();
        out.close();
    }
}
