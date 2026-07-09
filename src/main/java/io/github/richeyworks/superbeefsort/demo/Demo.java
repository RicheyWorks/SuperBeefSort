package io.github.richeyworks.superbeefsort.demo;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.superbeefsort.BeefSort;
import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.engine.SortRunResult;
import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.select.SelectionPolicy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Runnable showcase: {@code ./gradlew run}. Two workloads demonstrate the engine choosing different
 * strategies from the data profile, then feeding a real CSRBT {@link OrderedSet} and answering order
 * statistics. The observer prints the live profile -> select -> sort -> feed event trace.
 */
public final class Demo {

    public static void main(String[] args) {
        if (args.length > 0 && "organism".equals(args[0])) {
            // The full pipeline with live adaptation, recorded for CSRBT's arena visualizer:
            // ./gradlew run --args="organism"
            FullOrganismDemo.run();
            return;
        }
        if (args.length > 0 && "aquarium".equals(args[0])) {
            // The live tank: http://127.0.0.1:8077/ — SSE-streamed adaptation over an endless
            // workload playlist. ./gradlew run --args="aquarium"
            AquariumServer.run();
            return;
        }
        randomUniform();
        nearlySorted();
    }

    private static void randomUniform() {
        System.out.println("\n=== Scenario 1: 50,000 random integers in [0, 100000) ===");
        Random rnd = new Random(1);
        List<Integer> data = new ArrayList<>();
        for (int i = 0; i < 50_000; i++) {
            data.add(rnd.nextInt(100_000));
        }
        run(data);
    }

    private static void nearlySorted() {
        System.out.println("\n=== Scenario 2: 50,000 nearly-sorted integers (1% perturbed) ===");
        List<Integer> data = new ArrayList<>();
        for (int i = 0; i < 50_000; i++) {
            data.add(i);
        }
        Random rnd = new Random(2);
        for (int i = 0; i < 500; i++) {
            int a = rnd.nextInt(data.size());
            int b = rnd.nextInt(data.size());
            int t = data.get(a);
            data.set(a, data.get(b));
            data.set(b, t);
        }
        run(data);
    }

    private static void run(List<Integer> data) {
        OrderedSet<Integer> set = OrderedSet.withNaturalOrder(new RedBlackStrategy<Integer>());

        SortRunResult<Integer> r = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(data)
                .keyEncoder(KeyEncoder.ofInt(i -> i)) // unlocks counting / radix
                .policy(SelectionPolicy.SMART)
                .observe(event -> System.out.println("  " + event))
                .feedInto(set);

        DataProfile p = r.profile();
        System.out.printf("  profile : n=%d, sortedness=%.0f%%, ~%d distinct, distribution=%s%n",
                p.size(), p.sortednessRatio() * 100, p.distinctEstimate(), p.distribution());
        System.out.printf("  plan    : %s  (%s)%n", r.plan().strategy(), r.plan().rationale());
        System.out.printf("  sort    : %d comparisons, %d moves, %.2f ms%n",
                r.sortMetrics().comparisons(), r.sortMetrics().moves(), r.sortMetrics().elapsedMillis());
        System.out.printf("  feed    : %d inserted, %d duplicates, mode=%s, healthy=%s, %.2f ms%n",
                r.feedResult().inserted(), r.feedResult().duplicates(), r.feedResult().mode(),
                r.feedResult().healthy(), r.feedResult().elapsedMillis());
        System.out.printf("  csrbt   : size=%d, min=%d, median=%d, max=%d, 100th-smallest=%d%n",
                set.size(), set.minimum(), set.median(), set.maximum(), set.select(100));
    }
}
