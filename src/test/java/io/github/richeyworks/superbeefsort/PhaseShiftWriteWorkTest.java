package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.control.MorphPolicy;
import io.github.richeyworks.csrbt.strategy.AVLStrategy;
import io.github.richeyworks.csrbt.strategy.HybridStrategy;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.csrbt.strategy.SplayStrategy;
import io.github.richeyworks.csrbt.strategy.TreeStrategy;
import io.github.richeyworks.superbeefsort.csrbt.WorkloadAdaptation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The <b>write-axis</b> companion to {@link PhaseShiftWorkTest} (whose read-axis verdict — adaptive
 * +8.7%, no read-skew prize, splay reads structurally can't splay per ADR-004 — is recorded in
 * {@code docs/phase-shift-census-findings.md}). Reads are near-identical balanced-tree descents
 * across the morph family; <em>writes</em> are where the strategies genuinely diverge (fix-up work,
 * rotation discipline, splay's write-time restructuring) and where the ecosystem already encodes a
 * belief: WRITE_HEAVY advice exists, and the ADAPTIVE tier clamps it to Red-Black. This census
 * measures whether phase-shifting the read/write mix gives the control plane a real prize to chase.
 *
 * <p>Metric is <b>comparisons</b> through the shared {@link Comparable} wrapper — identical,
 * deterministic work for every arm including the JDK baselines. Comparisons meter descent work only;
 * rotations are invisible to it, so CSRBT arms also report a <b>rotations</b> column
 * ({@code rotationCount()}; JDK arms n/a, adaptive undercounts across a morph since the counter
 * resets with the engine — deltas are floor-guarded per the meter's contract). Population is
 * seeded-shuffled so no arm starts from the sorted-insert pathology.
 *
 * <p>Workload: N distinct keys from a 2N key space, then P phases alternating READ (100% uniform
 * contains) and CHURN (50/50 add/remove, uniform over the space). The adaptive arm uses the
 * {@code WorkloadAdaptation} data-plane facade so every op feeds the monitor, with
 * {@code maybeAdapt()} driven on a fixed cadence — the pilot's job, same as v1.
 *
 * <p>Prints a census to stdout and {@code build/phase-shift-write-census.txt}. Run it alone:
 * {@code .\gradlew :test --tests "*PhaseShiftWriteWorkTest" --info} (leading {@code :test} keeps
 * the filter off {@code :sbs-kernels-rust:test}).
 */
class PhaseShiftWriteWorkTest {

    /** Reset before each arm; incremented by every key comparison the arm performs. */
    static long comparisons = 0;

    /** An int key that tallies every comparison — the shared, structure-agnostic work meter. */
    static final class CountingKey implements Comparable<CountingKey> {
        final int v;

        CountingKey(int v) {
            this.v = v;
        }

        @Override
        public int compareTo(CountingKey o) {
            comparisons++;
            return Integer.compare(v, o.v);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof CountingKey c && c.v == v;
        }

        @Override
        public int hashCode() {
            return v;
        }
    }

    private interface Arm {
        /** Apply op to the structure; return true if it "took" (hit/inserted/removed). */
        boolean apply(int op, int key);
    }

    private record Result(String name, long comparisons, long effects, long[] perPhase,
                          long[] rotationsPerPhase, String[] strategyPerPhase) { }

    // ── config ──────────────────────────────────────────────────────────────────────────────────
    private static final int N = 100_000;          // initial population
    private static final int SPACE = 200_000;      // key universe (so adds find fresh keys)
    private static final int PHASES = 8;           // alternating READ (even) / CHURN (odd)
    private static final int PHASE_OPS = 200_000;
    private static final int ADAPT_EVERY = 2_000;
    private static final long SEED = 20_260_714L;

    private static final int OP_CONTAINS = 0;
    private static final int OP_ADD = 1;
    private static final int OP_REMOVE = 2;

    @Test
    void phaseShiftWriteComparisonCensus() throws IOException {
        // One seeded op stream, replayed identically by every arm.
        int[] ops = new int[PHASES * PHASE_OPS];
        int[] keys = new int[PHASES * PHASE_OPS];
        Random rnd = new Random(SEED);
        for (int p = 0; p < PHASES; p++) {
            boolean churn = (p % 2 == 1);
            for (int i = 0; i < PHASE_OPS; i++) {
                int idx = p * PHASE_OPS + i;
                keys[idx] = rnd.nextInt(SPACE);
                ops[idx] = churn ? (rnd.nextBoolean() ? OP_ADD : OP_REMOVE) : OP_CONTAINS;
            }
        }
        List<Integer> population = new ArrayList<>(N);
        for (int k = 0; k < N; k++) {
            population.add(k * 2);                 // evens: half the space, so churn keys are ~50% present
        }
        Collections.shuffle(population, new Random(SEED ^ 0xBEEF));

        List<Result> results = new ArrayList<>();
        results.add(staticArm("CSRBT_RedBlack", new RedBlackStrategy<>(), population, ops, keys));
        results.add(staticArm("CSRBT_AVL", new AVLStrategy<>(), population, ops, keys));
        results.add(staticArm("CSRBT_Splay", new SplayStrategy<>(), population, ops, keys));
        results.add(staticArm("CSRBT_Hybrid", new HybridStrategy<>(), population, ops, keys));
        results.add(mapArm("JDK_TreeMap", new TreeMap<>(), population, ops, keys));
        results.add(mapArm("JDK_SkipList", new ConcurrentSkipListMap<>(), population, ops, keys));
        results.add(adaptiveArm(population, ops, keys));

        // Correctness: set semantics make every arm's effect stream identical.
        long effects0 = results.get(0).effects();
        for (Result r : results) {
            assertEquals(effects0, r.effects(), r.name() + " effect count diverged — arms not comparable");
        }

        String report = render(results);
        System.out.println(report);
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build", "phase-shift-write-census.txt"), report);
    }

    /** A CSRBT set pinned to one strategy; rotations metered per phase. */
    private static Result staticArm(String name, TreeStrategy<CountingKey> strategy,
                                    List<Integer> population, int[] ops, int[] keys) {
        OrderedSet<CountingKey> set = OrderedSet.withNaturalOrder(strategy);
        for (int k : population) {
            set.add(new CountingKey(k));
        }
        Arm arm = (op, key) -> switch (op) {
            case OP_ADD -> set.add(new CountingKey(key));
            case OP_REMOVE -> set.remove(new CountingKey(key));
            default -> set.contains(new CountingKey(key));
        };
        long[] perPhase = new long[PHASES];
        long[] rot = new long[PHASES];
        long effects = loop(arm, ops, keys, perPhase, rot, set::rotationCount, null, null);
        return new Result(name, comparisons, effects, perPhase, rot, null);
    }

    /** A JDK ordered map — the real-world baselines (no rotation meter). */
    private static Result mapArm(String name, Map<CountingKey, Boolean> map,
                                 List<Integer> population, int[] ops, int[] keys) {
        for (int k : population) {
            map.put(new CountingKey(k), Boolean.TRUE);
        }
        Arm arm = (op, key) -> switch (op) {
            case OP_ADD -> map.put(new CountingKey(key), Boolean.TRUE) == null;
            case OP_REMOVE -> map.remove(new CountingKey(key)) != null;
            default -> map.containsKey(new CountingKey(key));
        };
        long[] perPhase = new long[PHASES];
        long effects = loop(arm, ops, keys, perPhase, null, null, null, null);
        return new Result(name, comparisons, effects, perPhase, null, null);
    }

    /** The self-tuning arm: data-plane facade feeds the monitor; maybeAdapt() on cadence. */
    private static Result adaptiveArm(List<Integer> population, int[] ops, int[] keys) {
        WorkloadAdaptation<CountingKey> a =
                WorkloadAdaptation.attach(OrderedSet.withNaturalOrder(new RedBlackStrategy<CountingKey>()),
                        MorphPolicy.defaults());
        for (int k : population) {
            a.set().add(new CountingKey(k));       // populate without feeding the monitor write-signal
        }
        Arm arm = (op, key) -> switch (op) {
            case OP_ADD -> a.add(new CountingKey(key));
            case OP_REMOVE -> a.remove(new CountingKey(key));
            default -> a.contains(new CountingKey(key));
        };
        long[] perPhase = new long[PHASES];
        long[] rot = new long[PHASES];
        String[] strat = new String[PHASES];
        long effects = loop(arm, ops, keys, perPhase, rot, () -> a.set().rotationCount(),
                a::maybeAdapt, p -> strat[p] = String.valueOf(a.currentStrategy()));
        // morph rebuild comparisons are inside the totals: the adaptive bill is honest
        return new Result("CSRBT_ADAPTIVE", comparisons, effects, perPhase, rot, strat);
    }

    private interface PhaseHook {
        void endOfPhase(int p);
    }

    /**
     * Reset the meter, replay the op stream, snapshot per-phase comparison/rotation deltas.
     * Rotation deltas are floor-guarded: a morph swaps the engine and resets its counter.
     * The adaptive arm's maybeAdapt() cadence rides the same loop via the phase-agnostic op index.
     */
    private static long loop(Arm arm, int[] ops, int[] keys, long[] perPhase, long[] rot,
                             java.util.function.LongSupplier rotations, Runnable adaptTick, PhaseHook hook) {
        comparisons = 0;
        long effects = 0;
        long lastCmp = 0;
        long lastRot = rotations == null ? 0 : rotations.getAsLong();
        for (int i = 0; i < ops.length; i++) {
            if (arm.apply(ops[i], keys[i])) {
                effects++;
            }
            if (adaptTick != null && (i + 1) % ADAPT_EVERY == 0) {
                adaptTick.run();
            }
            if ((i + 1) % PHASE_OPS == 0) {
                int p = (i + 1) / PHASE_OPS - 1;
                perPhase[p] = comparisons - lastCmp;
                lastCmp = comparisons;
                if (rot != null && rotations != null) {
                    long now = rotations.getAsLong();
                    rot[p] = Math.max(0, now - lastRot);
                    lastRot = now;
                }
                if (hook != null) {
                    hook.endOfPhase(p);
                }
            }
        }
        return effects;
    }

    private static String render(List<Result> results) {
        long totalOps = (long) PHASES * PHASE_OPS;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "%n=== Phase-shift WRITE census — N=%,d of %,d-space, %d phases × %,d ops (even=READ, odd=CHURN 50/50) ===%n",
                N, SPACE, PHASES, PHASE_OPS));
        sb.append(String.format("%-16s %16s %10s %14s%n", "arm", "comparisons", "cmp/op", "rotations"));
        for (Result r : results) {
            long totalRot = 0;
            if (r.rotationsPerPhase() != null) {
                for (long x : r.rotationsPerPhase()) {
                    totalRot += x;
                }
            }
            sb.append(String.format("%-16s %,16d %10.2f %14s%n", r.name(), r.comparisons(),
                    (double) r.comparisons() / totalOps,
                    r.rotationsPerPhase() == null ? "n/a" : String.format("%,d", totalRot)));
        }
        sb.append(String.format("%nper-phase comparisons (R C R C R C R C):%n"));
        for (Result r : results) {
            sb.append(String.format("  %-16s", r.name()));
            for (int p = 0; p < PHASES; p++) {
                sb.append(String.format(" %,11d", r.perPhase()[p]));
            }
            sb.append('\n');
            if (r.strategyPerPhase() != null) {
                sb.append(String.format("  %-16s", "  → strategy:"));
                for (int p = 0; p < PHASES; p++) {
                    sb.append(String.format(" %11s", r.strategyPerPhase()[p]));
                }
                sb.append('\n');
            }
        }
        Result adaptive = results.get(results.size() - 1);
        long bestOther = Long.MAX_VALUE;
        String bestName = "?";
        for (Result r : results) {
            if (!r.name().equals(adaptive.name()) && r.comparisons() < bestOther) {
                bestOther = r.comparisons();
                bestName = r.name();
            }
        }
        double delta = 100.0 * (bestOther - adaptive.comparisons()) / bestOther;
        sb.append(String.format(
                "%n>>> VERDICT: adaptive did %,d comparisons; best non-adaptive was %s at %,d  →  adaptive is %.1f%% %s%n",
                adaptive.comparisons(), bestName, bestOther, Math.abs(delta),
                delta > 0 ? "FEWER (adaptive wins)" : "MORE (adaptive loses)"));
        sb.append("    (comparisons meter descent work only — read the rotations column alongside; "
                + "a comparison win bought with a rotation explosion is not a win.)\n");
        return sb.toString();
    }
}
