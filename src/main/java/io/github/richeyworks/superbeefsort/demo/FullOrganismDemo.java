package io.github.richeyworks.superbeefsort.demo;

import io.github.richeyworks.csrbt.control.MorphController;
import io.github.richeyworks.csrbt.control.MorphPolicy;
import io.github.richeyworks.csrbt.export.TreeSessionRecorder;
import io.github.richeyworks.superbeefsort.BeefSort;
import io.github.richeyworks.superbeefsort.csrbt.AccessPolicy;
import io.github.richeyworks.superbeefsort.csrbt.WorkloadAdaptation;
import io.github.richeyworks.superbeefsort.stream.AdaptiveStreamSorter;
import io.github.richeyworks.superbeefsort.stream.StreamSortResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * The full organism, end to end, on the record: SuperBeefSort profiles and sorts the data,
 * constructs a born-optimal CSRBT set in O(n), wires it to CSRBT's control plane with a
 * profile-derived prior ({@code buildCoOptimized}), then drives three live regimes through it —
 * read-heavy uniform, hot-key skew, and a drifting bounded stream where the <em>sort</em> engine
 * re-selects its strategy and hands the <em>tree</em> engine the same moment
 * ({@code adaptiveStream(adaptation, …)}). Every control-plane decision (morph attempts, window
 * evictions, self-repairs) is captured by CSRBT's {@link TreeSessionRecorder} and written to
 * {@code docs/organism-session.json} — load it in CSRBT's zero-dependency visualizer
 * ({@code CSRBT/demo/visualizer.html}) and watch the decisions replay over snapshots of the tree.
 *
 * <p>Run: {@code ./gradlew run --args="organism"}. Deterministic (fixed seeds). Honesty note:
 * whether a morph <em>commits</em> is the {@link MorphPolicy}'s call — margin, stability, cooldown —
 * against the live cost model; a run full of explained HOLDs is the anti-thrash machinery working,
 * not the demo failing. The point is that every decision is observable and replayable.</p>
 */
public final class FullOrganismDemo {

    private static final int INITIAL_KEYS = 20_000;
    private static final int KEY_SPACE = 60_000;
    private static final int PHASE_OPS = 12_000;
    private static final int ADAPT_EVERY = 3_000;
    private static final int STREAM_BATCH = 4_000;
    private static final int WINDOW = 5_000;

    private FullOrganismDemo() {
    }

    public static void run() {
        System.out.println("=== The full organism: profile -> born-optimal -> live adaptation, recorded ===");

        // 1. Birth: profile + sort 20k uniform keys, construct in O(n), attach the control plane
        //    with a prior derived from the realized run (the "two engines talking" wiring).
        Random rnd = new Random(42);
        List<Integer> initial = new ArrayList<>(INITIAL_KEYS);
        for (int i = 0; i < INITIAL_KEYS; i++) {
            initial.add(rnd.nextInt(KEY_SPACE));
        }
        BeefSort<Integer> beef = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(initial)
                .accessPattern(AccessPolicy.BALANCED)
                .observe(e -> System.out.println("  [sort] " + e));
        WorkloadAdaptation<Integer> adaptation = beef.buildCoOptimized(MorphPolicy.defaults());
        System.out.printf("%n  Born: %s, %d keys, strategy=%s%n",
                "co-optimized OrderedSet", adaptation.set().size(),
                adaptation.set().getStrategy().getClass().getSimpleName());

        // 2. Flight recorder: CSRBT's own session recorder becomes the set's event listener
        //    (replacing the build-time observer bridge — tree events now go to the tape).
        TreeSessionRecorder<Integer> recorder = TreeSessionRecorder.attach(adaptation.set());

        // 3. Regime A — read-heavy, uniform: strict balance should look good to the scorer.
        phase("A: read-heavy uniform", adaptation, ops -> {
            int k = rnd.nextInt(KEY_SPACE);
            if (ops % 5 == 0) {
                if (rnd.nextBoolean()) adaptation.add(k); else adaptation.remove(k);
            } else {
                adaptation.contains(k);   // one walk: answers AND records the realized depth
            }
        });

        // 4. Regime B — hot-key skew: eight keys take ~90% of reads; self-adjustment territory.
        int[] hot = new int[8];
        for (int i = 0; i < hot.length; i++) {
            hot[i] = rnd.nextInt(KEY_SPACE);
            adaptation.add(hot[i]);
        }
        phase("B: hot-key skew (8 keys, ~90% of reads)", adaptation, ops -> {
            if (rnd.nextInt(10) < 9) {
                adaptation.contains(hot[rnd.nextInt(hot.length)]);
            } else {
                adaptation.add(rnd.nextInt(KEY_SPACE));
            }
        });

        // 5. Regime C — the drifting stream: bounded window, and the drift verdict reaches BOTH
        //    engines (sort re-selects; tree gets one policy-gated maybeAdapt at the same moment).
        System.out.printf("%n  -- Regime C: drifting bounded stream (window=%d) --%n", WINDOW);
        AdaptiveStreamSorter<Integer> stream = beef.adaptiveStream(adaptation, WINDOW);
        for (int batch = 0; batch < 6; batch++) {
            List<Integer> data = new ArrayList<>(STREAM_BATCH);
            if (batch < 3) {
                for (int i = 0; i < STREAM_BATCH; i++) data.add(rnd.nextInt(KEY_SPACE));   // uniform
            } else {
                int base = KEY_SPACE + batch * STREAM_BATCH;                               // drift:
                for (int i = 0; i < STREAM_BATCH; i++) data.add(base + i);                 // sorted, new range
            }
            StreamSortResult<Integer> r = stream.accept(data);
            System.out.printf("  batch %d: strategy=%s reselected=%s drift=%.3f (%s)%n",
                    r.batchIndex(), r.plan().strategy().value(), r.reselected(),
                    r.driftScore(), r.driftReason());
        }

        // 6. The verdict, and the tape.
        System.out.printf("%n  Final: size=%d (window %d), median=%s, p95=%s%n",
                adaptation.set().size(), adaptation.set().getMaxSize(),
                adaptation.set().median(), adaptation.set().percentile(95));
        System.out.println("  Adaptation report: " + adaptation.adaptationReport());
        System.out.printf("  Recorder: %d decision points over %d effective ops%n",
                recorder.decisionCount(), recorder.opCount());

        adaptation.set().setEventListener(null);   // detach per the recorder contract
        Path out = Path.of("docs", "organism-session.json");
        try {
            Files.createDirectories(out.getParent());
            Files.writeString(out, recorder.toJson());
            System.out.printf("%n  Session written: %s%n", out.toAbsolutePath());
            System.out.println("  Replay it: open CSRBT/demo/visualizer.html and load that file.");
        } catch (IOException e) {
            System.out.println("  (could not write session file: " + e.getMessage() + ")");
        }
    }

    /** Drive one regime: {@code PHASE_OPS} operations with a policy-gated evaluation every {@code ADAPT_EVERY}. */
    private static void phase(String name, WorkloadAdaptation<Integer> adaptation, OpStep step) {
        System.out.printf("%n  -- Regime %s --%n", name);
        for (int ops = 1; ops <= PHASE_OPS; ops++) {
            step.apply(ops);
            if (ops % ADAPT_EVERY == 0) {
                MorphController.MorphResult r = adaptation.maybeAdapt();
                System.out.printf("  [tree] eval @%d: %s%n", ops,
                        r.morphed() ? "MORPH " + r.from() + " -> " + r.to() : "hold (" + r.reason() + ")");
            }
        }
    }

    @FunctionalInterface
    private interface OpStep {
        void apply(int opIndex);
    }
}
