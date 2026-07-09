package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.superbeefsort.demo.PercentileService;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The duplicate trick that makes a set into a quantile store: {@code (value << 22) | seq} must
 * round-trip, order by value, and keep repeated observations distinct — and a windowed set over
 * encoded observations must answer percentiles over the raw values.
 */
class PercentileCodecTest {

    @Test
    void roundTripsAndClamps() {
        Random rnd = new Random(1);
        for (int i = 0; i < 10_000; i++) {
            long v = Math.abs(rnd.nextLong()) % (1L << 40);
            assertEquals(v, PercentileService.decode(PercentileService.encode(v, i)));
        }
        assertEquals(0, PercentileService.decode(PercentileService.encode(-5, 1)), "negatives clamp to 0");
    }

    @Test
    void ordersByValueAndKeepsDuplicatesDistinct() {
        long a1 = PercentileService.encode(100, 1);
        long a2 = PercentileService.encode(100, 2);
        long b = PercentileService.encode(101, 0);
        assertTrue(a1 != a2, "same value, different observations, distinct keys");
        assertTrue(a1 < b && a2 < b, "value-major ordering");
    }

    @Test
    void windowedSetOfObservationsAnswersQuantiles() {
        OrderedSet<Long> set = OrderedSet.withNaturalOrder(new RedBlackStrategy<Long>());
        set.setMaxSize(1_000);
        long seq = 0;
        // 2,000 observations of values 0..1999 ascending: the window keeps the newest 1,000
        // (values 1000..1999), so quantiles must come from that window alone.
        for (int v = 0; v < 2_000; v++) {
            set.add(PercentileService.encode(v, seq++));
        }
        assertEquals(1_000, set.size());
        assertEquals(1_000, PercentileService.decode(set.minimum()), "oldest observations evicted");
        assertEquals(1_999, PercentileService.decode(set.maximum()));
        long p50 = PercentileService.decode(set.percentile(50));
        assertTrue(p50 >= 1_490 && p50 <= 1_510, "median of the window, was " + p50);
        long p99 = PercentileService.decode(set.percentile(99));
        assertTrue(p99 >= 1_985, "p99 near the window's top, was " + p99);
        // Duplicates count: 500 observations of the SAME value must occupy 500 window slots.
        for (int i = 0; i < 500; i++) {
            set.add(PercentileService.encode(42, seq++));
        }
        assertEquals(1_000, set.size(), "window bound holds under duplicates");
        assertEquals(42, PercentileService.decode(set.minimum()),
                "the repeated value is genuinely present many times");
    }
}
