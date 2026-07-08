package io.github.richeyworks.superbeefsort.intelligence;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * A minimal circuit breaker for the SbsIntelligence transport (Phase 4b). Pure and grpc-free, so it lives in
 * core and is unit-testable; the gRPC {@code GrpcIntelligenceClient} (optional module) wraps each RPC with it
 * to stop hammering a down or slow service and to fall back to the local selector promptly.
 *
 * <p>States:</p>
 * <ul>
 *   <li><b>CLOSED</b> — allow requests (normal);</li>
 *   <li><b>OPEN</b> — skip requests (short-circuit to the local fallback) until the cooldown elapses;</li>
 *   <li><b>HALF_OPEN</b> — allow a single probe after the cooldown; a success closes, a failure re-opens.</li>
 * </ul>
 *
 * <p>{@code failureThreshold} consecutive failures open the breaker; any success closes it and resets the
 * count. The clock is injectable for deterministic tests. The few atomics make it safe for the selector's
 * concurrent use without locking.</p>
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long cooldownMillis;
    private final LongSupplier clock;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong openedAt = new AtomicLong(0L);
    private volatile State state = State.CLOSED;

    /** Default clock = {@link System#currentTimeMillis()}. */
    public CircuitBreaker(int failureThreshold, long cooldownMillis) {
        this(failureThreshold, cooldownMillis, System::currentTimeMillis);
    }

    public CircuitBreaker(int failureThreshold, long cooldownMillis, LongSupplier clock) {
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("failureThreshold must be > 0: " + failureThreshold);
        }
        if (cooldownMillis < 0) {
            throw new IllegalArgumentException("cooldownMillis must be >= 0: " + cooldownMillis);
        }
        this.failureThreshold = failureThreshold;
        this.cooldownMillis = cooldownMillis;
        this.clock = clock;
    }

    /**
     * Whether a request should be attempted now. When OPEN, transitions to HALF_OPEN (allowing one probe) once
     * the cooldown has elapsed; otherwise returns {@code false} to short-circuit to the local fallback.
     */
    public boolean allowRequest() {
        State s = state;
        if (s != State.OPEN) {
            return true; // CLOSED or HALF_OPEN
        }
        if (clock.getAsLong() - openedAt.get() >= cooldownMillis) {
            state = State.HALF_OPEN; // let a single probe through
            return true;
        }
        return false;
    }

    /** A successful call: reset the failure count and close the breaker. */
    public void recordSuccess() {
        consecutiveFailures.set(0);
        state = State.CLOSED;
    }

    /** A failed call: open the breaker if a HALF_OPEN probe failed or the threshold is reached. */
    public void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (state == State.HALF_OPEN || failures >= failureThreshold) {
            state = State.OPEN;
            openedAt.set(clock.getAsLong());
        }
    }

    public State state() {
        return state;
    }
}
