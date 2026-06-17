package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.superbeefsort.strategy.RadixPlan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The entropy-aware radix planner: it must cover the range width, adapt the base to n, and beat a fixed schedule. */
class RadixPlanTest {

    @Test
    void zeroWidthMeansNoPasses() {
        assertEquals(0, RadixPlan.forWidth(0, 1000).passes(), "all keys equal -> nothing to distinguish");
        assertEquals(0, RadixPlan.forWidth(-5, 1000).passes());
    }

    @Test
    void everyPlanCoversItsWidthWithValidBase() {
        for (int w = 1; w <= 64; w++) {
            for (int n : new int[]{4, 1_000, 1_000_000}) {
                RadixPlan p = RadixPlan.forWidth(w, n);
                assertTrue(p.passes() * p.bitsPerPass() >= w, "plan must cover w=" + w + " (n=" + n + ")");
                assertTrue(p.bitsPerPass() >= 1 && p.bitsPerPass() <= RadixPlan.MAX_BITS, "base in range");
                assertEquals(1 << p.bitsPerPass(), p.radix());
            }
        }
    }

    @Test
    void narrowRangeIsASinglePass() {
        // 10 varying bits over a million items -> one ~10-bit pass, not eight.
        assertEquals(1, RadixPlan.forWidth(10, 1_000_000).passes());
    }

    @Test
    void wideKeysUseFewerThanEightPasses() {
        RadixPlan p = RadixPlan.forWidth(64, 1_000_000);
        assertTrue(p.passes() <= 8, "full-width keys should beat the old fixed 8 passes; got " + p.passes());
        assertTrue(p.passes() * p.bitsPerPass() >= 64);
    }

    @Test
    void smallNAvoidsAFatCountArray() {
        // With only a handful of items, a 2^b bucket array shouldn't dominate: keep the base modest.
        assertTrue(RadixPlan.forWidth(32, 8).bitsPerPass() <= 8);
    }
}
