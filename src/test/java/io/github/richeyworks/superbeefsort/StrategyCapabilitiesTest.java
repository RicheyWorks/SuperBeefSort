package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.superbeefsort.core.StrategyCapabilities;
import io.github.richeyworks.superbeefsort.core.StrategyCapabilities.AuxMemory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link AuxMemory} growth classes and the builder default. {@code estimatedBytes} is the signal the
 * memory-budgeted STABLE routing in {@code RuleBasedStrategySelector} / {@code CostModelStrategySelector}
 * reasons over, so this pins both the per-class arithmetic and the exact merge-vs-WikiSort crossover.
 */
class StrategyCapabilitiesTest {

    private static final long REF = 8L;                 // bytes per object reference, per AuxMemory
    private static final long BUDGET = 16L << 20;       // 16 MB, the selectors' aux-memory budget

    @Test
    void linearIsEightBytesPerElement() {
        assertEquals(0L, AuxMemory.LINEAR.estimatedBytes(0));
        assertEquals(REF, AuxMemory.LINEAR.estimatedBytes(1));
        assertEquals(REF * 1_000, AuxMemory.LINEAR.estimatedBytes(1_000));
        assertEquals(REF * (1L << 21), AuxMemory.LINEAR.estimatedBytes(1L << 21));
    }

    @Test
    void constantIsAlwaysZero() {
        for (long n : new long[] {0, 1, 1_000, 1L << 21, Integer.MAX_VALUE}) {
            assertEquals(0L, AuxMemory.CONSTANT.estimatedBytes(n), "CONSTANT at n=" + n);
        }
    }

    @Test
    void logarithmicIsEightTimesCeilLog2() {
        // Non-power-of-two inputs with comfortable ceil margins, so Math.log's 1-ulp slack can't tip the
        // result across an integer (powers of two would be FP-fragile). n=2 is exact: log(2)/log(2) == 1.0.
        assertEquals(REF * 1, AuxMemory.LOGARITHMIC.estimatedBytes(2));          // log2(2)   = 1
        assertEquals(REF * 2, AuxMemory.LOGARITHMIC.estimatedBytes(3));          // log2(3)   ~ 1.585 -> 2
        assertEquals(REF * 5, AuxMemory.LOGARITHMIC.estimatedBytes(17));         // log2(17)  ~ 4.087 -> 5
        assertEquals(REF * 10, AuxMemory.LOGARITHMIC.estimatedBytes(1_000));     // log2(1000)~ 9.966 -> 10
        assertEquals(REF * 20, AuxMemory.LOGARITHMIC.estimatedBytes(1_000_000)); // log2(1e6) ~ 19.93 -> 20
    }

    @Test
    void negativeOrTinyNeverGoesNegative() {
        assertEquals(0L, AuxMemory.LINEAR.estimatedBytes(-5));
        assertEquals(0L, AuxMemory.CONSTANT.estimatedBytes(-5));
        // LOGARITHMIC floors at log2(2) == 1 ref rather than producing log2(0)/log2(1).
        assertEquals(REF, AuxMemory.LOGARITHMIC.estimatedBytes(-5));
        assertEquals(REF, AuxMemory.LOGARITHMIC.estimatedBytes(1));
    }

    @Test
    void builderDefaultsToConstant() {
        assertEquals(AuxMemory.CONSTANT, StrategyCapabilities.builder().build().auxMemory());
    }

    @Test
    void builderRecordsExplicitAuxMemory() {
        assertEquals(AuxMemory.LINEAR,
                StrategyCapabilities.builder().auxMemory(AuxMemory.LINEAR).build().auxMemory());
    }

    /**
     * The crossover the selectors encode: merge's LINEAR scratch meets the 16 MB budget at exactly
     * 2^21 elements (8 B * 2^21 == 16 MB), reproducing the previous {@code size >= 2^21} gate byte-for-byte.
     */
    @Test
    void mergeScratchMeetsBudgetExactlyAtTwoToThe21() {
        assertEquals(BUDGET, AuxMemory.LINEAR.estimatedBytes(1L << 21));
        assertTrue(AuxMemory.LINEAR.estimatedBytes(1L << 21) >= BUDGET);
        assertFalse(AuxMemory.LINEAR.estimatedBytes((1L << 21) - 1) >= BUDGET);
    }
}
