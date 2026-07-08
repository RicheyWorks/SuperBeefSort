package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.superbeefsort.intelligence.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure circuit breaker that gates the SbsIntelligence transport (deterministic via an injected clock). */
class CircuitBreakerTest {

    @Test
    void closedAllowsUntilThresholdThenOpens() {
        CircuitBreaker cb = new CircuitBreaker(3, 1000, () -> 0L);
        assertTrue(cb.allowRequest());
        cb.recordFailure();
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.CLOSED, cb.state()); // 2 < 3
        cb.recordFailure();                                    // 3rd consecutive -> open
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        assertFalse(cb.allowRequest());                        // within cooldown -> short-circuit
    }

    @Test
    void opensThenHalfOpensAfterCooldownAndClosesOnSuccess() {
        AtomicLong now = new AtomicLong(0);
        CircuitBreaker cb = new CircuitBreaker(1, 100, now::get);
        cb.recordFailure();                                    // threshold 1 -> open at t=0
        assertFalse(cb.allowRequest());                        // t=0 within cooldown
        now.set(100);                                          // cooldown elapsed
        assertTrue(cb.allowRequest());                         // one probe allowed
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());
        cb.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        assertTrue(cb.allowRequest());
    }

    @Test
    void halfOpenFailureReopens() {
        AtomicLong now = new AtomicLong(0);
        CircuitBreaker cb = new CircuitBreaker(1, 100, now::get);
        cb.recordFailure();                                    // open at t=0
        now.set(100);
        assertTrue(cb.allowRequest());                         // -> half-open
        cb.recordFailure();                                    // probe failed -> reopen at t=100
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        assertFalse(cb.allowRequest());                        // within the new cooldown
        now.set(200);
        assertTrue(cb.allowRequest());                         // cooldown again -> half-open
    }

    @Test
    void successResetsConsecutiveFailureCount() {
        CircuitBreaker cb = new CircuitBreaker(3, 1000, () -> 0L);
        cb.recordFailure();
        cb.recordFailure();
        cb.recordSuccess();                                    // reset
        cb.recordFailure();
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.CLOSED, cb.state()); // only 2 failures since the reset
    }

    @Test
    void rejectsBadConfig() {
        assertThrows(IllegalArgumentException.class, () -> new CircuitBreaker(0, 100));
        assertThrows(IllegalArgumentException.class, () -> new CircuitBreaker(1, -1));
    }
}
