package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.control.MorphPolicy;
import io.github.richeyworks.csrbt.strategy.AVLStrategy;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.csrbt.strategy.SplayStrategy;
import io.github.richeyworks.csrbt.strategy.TreeStrategy;
import io.github.richeyworks.superbeefsort.csrbt.WorkloadAdaptation;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The thesis test (measurement, not pass/fail): does the CSRBT self-tuning index actually do less
 * work than the best <em>static</em> tree — and than the JDK's own ordered maps — on a workload
 * whose access skew <b>shifts in phases</b>? That is the one setting where adaptivity could earn its
 * keep: no single static shape is optimal across the whole run, so if the morph machinery tracks the
 * shift it wins; if it doesn't, the adaptive angle is elegant but inert. Everything is CPU-bound and
 * in-memory here (no I/O to drown the index — the opposite of the SmokeHouse D1 result).
 *
 * <p>Metric is <b>comparisons</b>, counted through a {@link Comparable} key wrapper so it is
 * identical work for CSRBT's trees and for {@link TreeMap} / {@link ConcurrentSkipListMap}, and it is
 * deterministic and machine-independent (unlike wall-clock). Comparisons ≈ search-path length ≈ the
 * thing a good tree shape shortens. The adaptive arm's morph rebuilds are counted too — its total is
 * honest, morph cost included. Reads only, over a fixed populated set: the purest test of read-path
 * adaptation. Writes (where the RB-vs-splay trade flips again) are a separate axis, deliberately left
 * out of v1 so the skew signal is clean.
 *
 * <p>Workload: N distinct keys populated, then P phases of reads that alternate UNIFORM (balanced
 * trees win) and SKEWED (92% of reads on a {@code HOT}-key hot set — a splay tree wins by bubbling
 * them to the root). A tree pinned to any single strategy pays in the phases that strategy is wrong
 * for; the question is whether adaptive pays less than all of them combined.
 *
 * <p>Prints a census to stdout and to {@code build/phase-shift-census.txt}. Run it explicitly:
 * {@code ./gradlew test --tests "*PhaseShiftWorkTest" --info}.
 */
class PhaseShiftWorkTest {

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

    private interface Reader {
        boolean read(int key);
    }

    private record Result(String name, long comparisons, long hits, long[] perPhase, String[] strategyPerPhase) { }

    // ── config ──────────────────────────────────────────────────────────────────────────────────
    private static final int N = 100_000;          // distinct keys populated
    private static final int HOT = 1_000;          // skewed phases concentrate here
    private static final int PHASES = 8;           // alternating UNIFORM (even) / SKEWED (odd)
    private static final int PHASE_OPS = 200_000;  // reads per phase (long enough to morph within one)
    private static final int ADAPT_EVERY = 2_000;  // drive maybeAdapt() this often (the pilot's job)
    private static final long SEED = 20_260_712L;

    @Test
    @Disabled("measurement harness, ~3 min — verdict recorded in docs/phase-shift-census-findings.md "
            + "(2026-07-14: adaptive +8.7% vs pinned RB; no read-skew prize, splay reads can't splay per ADR-004). "
            + "Remove this annotation to re-run or to build the write-heavy variant.")
    void phaseShiftComparisonCensus() throws IOException {
        int[] keys = new int[PHASES * PHASE_OPS];
        Random rnd = new Random(SEED);
        for (int p = 0; p < PHASES; p++) {
            boolean skewed = (p % 2 == 1);
            for (int i = 0; i < PHASE_OPS; i++) {
                int k = skewed
                        ? (rnd.nextInt(100) < 92 ? rnd.nextInt(HOT) : rnd.nextInt(N))
                        : rnd.nextInt(N);
                keys[p * PHASE_OPS + i] = k;
            }
        }

        List<Result> results = new ArrayList<>();
        results.add(staticArm("CSRBT_RedBlack", new RedBlackStrategy<>(), keys));
        results.add(staticArm("CSRBT_AVL", new AVLStrategy<>(), keys));
        results.add(staticArm("CSRBT_Splay", new SplayStrategy<>(), keys));
        results.add(mapArm("JDK_TreeMap", new TreeMap<>(), keys));
        results.add(mapArm("JDK_SkipList", new ConcurrentSkipListMap<>(), keys));
        results.add(adaptiveArm(keys));

        // Correctness: every arm replayed the identical workload over the identical key set.
        long hits0 = results.get(0).hits();
        for (Result r : results) {
            assertEquals(hits0, r.hits(), r.name() + " hit count diverged — arms not comparable");
        }

        String report = render(results);
        System.out.println(report);
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build", "phase-shift-census.txt"), report);
    }

    /** A CSRBT set pinned to one strategy. */
    private static Result staticArm(String name, TreeStrategy<CountingKey> strategy, int[] keys) {
        OrderedSet<CountingKey> set = OrderedSet.withNaturalOrder(strategy);
        for (int k = 0; k < N; k++) {
            set.add(new CountingKey(k));
        }
        long[] perPhase = new long[PHASES];
        long hits = readLoop(k -> set.contains(new CountingKey(k)), keys, perPhase);
        return new Result(name, comparisons, hits, perPhase, null);
    }

    /** A JDK ordered map (TreeMap = red-black, SkipList = probabilistic) — the real-world baselines. */
    private static Result mapArm(String name, Map<CountingKey, Boolean> map, int[] keys) {
        for (int k = 0; k < N; k++) {
            map.put(new CountingKey(k), Boolean.TRUE);
        }
        long[] perPhase = new long[PHASES];
        long hits = readLoop(k -> map.containsKey(new CountingKey(k)), keys, perPhase);
        return new Result(name, comparisons, hits, perPhase, null);
    }

    /** The self-tuning arm: same reads, plus a driven maybeAdapt() and per-phase strategy capture. */
    private static Result adaptiveArm(int[] keys) {
        WorkloadAdaptation<CountingKey> a =
                WorkloadAdaptation.attach(OrderedSet.withNaturalOrder(new RedBlackStrategy<CountingKey>()), MorphPolicy.defaults());
        for (int k = 0; k < N; k++) {
            a.set().add(new CountingKey(k));       // populate without feeding the monitor write-signal
        }
        comparisons = 0;
        long hits = 0;
        long last = 0;
        long[] perPhase = new long[PHASES];
        String[] strat = new String[PHASES];
        for (int i = 0; i < keys.length; i++) {
            if (a.contains(new CountingKey(keys[i]))) {
                hits++;
            }
            if ((i + 1) % ADAPT_EVERY == 0) {
                a.maybeAdapt();                    // morph rebuilds (if any) are counted below
            }
            if ((i + 1) % PHASE_OPS == 0) {
                int p = (i + 1) / PHASE_OPS - 1;
                perPhase[p] = comparisons - last;
                last = comparisons;
                strat[p] = String.valueOf(a.currentStrategy());
            }
        }
        return new Result("CSRBT_ADAPTIVE", comparisons, hits, perPhase, strat);
    }

    /** Reset the meter, replay the read stream, snapshot per-phase comparison deltas; return hit count. */
    private static long readLoop(Reader reader, int[] keys, long[] perPhase) {
        comparisons = 0;
        long hits = 0;
        long last = 0;
        for (int i = 0; i < keys.length; i++) {
            if (reader.read(keys[i])) {
                hits++;
            }
            if ((i + 1) % PHASE_OPS == 0) {
                int p = (i + 1) / PHASE_OPS - 1;
                perPhase[p] = comparisons - last;
                last = comparisons;
            }
        }
        return hits;
    }

    private static String render(List<Result> results) {
        long totalOps = (long) PHASES * PHASE_OPS;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "%n=== Phase-shift comparison census — N=%,d, %d phases × %,d reads (even=UNIFORM, odd=SKEWED 92%%→hot-%d) ===%n",
                N, PHASES, PHASE_OPS, HOT));
        sb.append(String.format("%-16s %16s %10s%n", "arm", "comparisons", "cmp/read"));
        for (Result r : results) {
            sb.append(String.format("%-16s %,16d %10.2f%n", r.name(), r.comparisons(), (double) r.comparisons() / totalOps));
        }
        sb.append(String.format("%nper-phase comparisons (U S U S U S U S):%n"));
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
        long bestOther = results.stream()
                .filter(r -> !r.name().equals(adaptive.name()))
                .mapToLong(Result::comparisons).min().orElse(Long.MAX_VALUE);
        String bestName = results.stream()
                .filter(r -> !r.name().equals(adaptive.name()))
                .min((x, y) -> Long.compare(x.comparisons(), y.comparisons())).map(Result::name).orElse("?");
        double delta = 100.0 * (bestOther - adaptive.comparisons()) / bestOther;
        sb.append(String.format(
                "%n>>> VERDICT: adaptive did %,d comparisons; best non-adaptive was %s at %,d  →  adaptive is %.1f%% %s%n",
                adaptive.comparisons(), bestName, bestOther, Math.abs(delta),
                delta > 0 ? "FEWER (adaptive wins)" : "MORE (adaptive loses)"));
        sb.append("    (a win here is necessary but not sufficient — if positive, confirm it survives wall-clock incl. morph overhead via JMH.)\n");
        return sb.toString();
    }
}
