package io.github.richeyworks.superbeefsort.bench;

import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.core.StrategyId;
import io.github.richeyworks.superbeefsort.registry.StrategyRegistry;
import io.github.richeyworks.superbeefsort.strategy.RadixSortStrategy;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Head-to-head: {@code radix.lsd} (pure Java, entropy-aware) vs {@code radix.lsd.rust}
 * (native Rust kernel via Panama FFM) across a range of input sizes.
 *
 * <p>The native benchmark no-ops gracefully when {@code radix.lsd.rust} is not in the
 * registry (JDK &lt; 22, missing cdylib, or native access not granted), so {@code ./gradlew jmh}
 * is always safe to run. Re-run with {@code --enable-native-access=ALL-UNNAMED} in the JVM
 * args (see build.gradle.kts JMH configuration) to exercise the native path.</p>
 *
 * <p>The selector must <em>not</em> prefer the native strategy until these results confirm a
 * positive margin above the FFM marshaling cost. Run:
 * {@code ./gradlew jmh -Pbench=RadixNativeBenchmark}</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class RadixNativeBenchmark {

    @Param({"1000", "10000", "100000", "500000"})
    public int n;

    private List<Integer> data;
    private final Comparator<Integer> cmp = Comparator.naturalOrder();
    private final KeyEncoder<Integer> encoder = KeyEncoder.ofInt(i -> i);

    private final SortStrategy<Integer> javaRadix = new RadixSortStrategy<>();
    private SortStrategy<Integer> rustRadix;

    @Setup(Level.Trial)
    public void setup() {
        Random rng = new Random(42);
        data = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            data.add(rng.nextInt(2 * n));
        }
        // Look up the native strategy; null when the kernel module is absent
        StrategyRegistry registry = StrategyRegistry.withDefaults();
        StrategyId rustId = StrategyId.of("radix.lsd.rust");
        rustRadix = registry.contains(rustId) ? registry.get(rustId) : null;
    }

    @Benchmark
    public Integer javaRadixLsd() {
        SortBuffer<Integer> buf = SortBuffer.of(data, cmp, encoder);
        javaRadix.sort(buf, SortContext.noop());
        return buf.get(0);
    }

    @Benchmark
    public Integer rustRadixLsd() {
        if (rustRadix == null) {
            // Native strategy absent: return a deterministic sentinel so JMH can't constant-fold
            return data.get(0);
        }
        SortBuffer<Integer> buf = SortBuffer.of(data, cmp, encoder);
        rustRadix.sort(buf, SortContext.noop());
        return buf.get(0);
    }
}
