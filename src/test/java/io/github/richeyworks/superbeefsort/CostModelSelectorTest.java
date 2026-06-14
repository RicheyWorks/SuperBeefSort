package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.profile.Distribution;
import io.github.richeyworks.superbeefsort.profile.KeyStats;
import io.github.richeyworks.superbeefsort.profile.ProfileDepth;
import io.github.richeyworks.superbeefsort.registry.StrategyRegistry;
import io.github.richeyworks.superbeefsort.select.CostModelStrategySelector;
import io.github.richeyworks.superbeefsort.select.SelectionPolicy;
import io.github.richeyworks.superbeefsort.select.SortPlan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The cost-model selector's choices across constructed profiles. */
class CostModelSelectorTest {

    private final CostModelStrategySelector selector = new CostModelStrategySelector();
    private final StrategyRegistry registry = StrategyRegistry.withDefaults();

    private String pick(DataProfile p) {
        SortPlan plan = selector.select(p, SelectionPolicy.SMART, registry);
        return plan.strategy().value();
    }

    private static DataProfile profile(int n, double sortedness, KeyStats ks) {
        return new DataProfile(n, sortedness, false, ProfileDepth.SHALLOW, n, ks, Distribution.UNKNOWN);
    }

    @Test
    void tinyInputPicksInsertion() {
        assertEquals("insertion", pick(profile(10, 0.5, null)));
    }

    @Test
    void boundedIntegerDataPicksCounting() {
        assertEquals("counting", pick(profile(50_000, 0.5, new KeyStats(0, 50_000, true))));
    }

    @Test
    void wideRangeIntegerDataPicksRadix() {
        // range too wide for counting (countingFeasible = false); radix (~8n) beats n log n
        assertEquals("radix.lsd", pick(profile(50_000, 0.5, new KeyStats(0, 1L << 30, false))));
    }

    @Test
    void generalDataNoEncoderPicksIntrosort() {
        assertEquals("intro", pick(profile(50_000, 0.5, null)));
    }

    @Test
    void nearlySortedNoEncoderPicksTimsort() {
        assertEquals("jdk.timsort", pick(profile(50_000, 0.99, null)));
    }

    @Test
    void nearlySortedBoundedIntegerStillPicksCounting() {
        // The improvement over the rule-based selector: counting (~2n) beats run-aware TimSort (~14n)
        // even on nearly-sorted data, because counting ignores existing order and is linear.
        assertEquals("counting", pick(profile(50_000, 0.98, new KeyStats(0, 50_000, true))));
    }
}
