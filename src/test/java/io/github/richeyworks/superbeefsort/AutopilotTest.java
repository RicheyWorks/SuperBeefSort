package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.control.MorphPolicy;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.superbeefsort.csrbt.Autopilot;
import io.github.richeyworks.superbeefsort.csrbt.WorkloadAdaptation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The self-driving facade: cycles evaluate through the policy gates, and the mutual-exclusion
 * front door keeps CSRBT's single-threaded control-plane contract intact under concurrent callers.
 */
class AutopilotTest {

    private static Autopilot<Integer> pilot() {
        OrderedSet<Integer> set = OrderedSet.withNaturalOrder(new RedBlackStrategy<Integer>());
        // Hour-long cadence: the scheduler never fires during a test; pilotOnce() drives manually.
        return Autopilot.of(WorkloadAdaptation.attach(set, MorphPolicy.defaults()), Duration.ofHours(1));
    }

    @Test
    void pilotOnceEvaluatesAndReports() {
        try (Autopilot<Integer> p = pilot()) {
            for (int i = 0; i < 500; i++) {
                p.add(i);
            }
            assertTrue(p.contains(250));
            String verdict = p.pilotOnce();
            assertNotNull(verdict);
            assertTrue(verdict.startsWith("hold") || verdict.startsWith("morph"),
                    "a verdict is always explained: " + verdict);
            assertEquals(1, p.cycles());
            assertEquals(verdict, p.lastVerdict());
        }
    }

    @Test
    void frontDoorSurvivesConcurrentCallersWithPilotCycles() throws InterruptedException {
        try (Autopilot<Integer> p = pilot()) {
            Thread[] workers = new Thread[4];
            for (int t = 0; t < workers.length; t++) {
                final long seed = 100L + t;
                workers[t] = new Thread(() -> {
                    Random rnd = new Random(seed);
                    for (int i = 0; i < 5_000; i++) {
                        int k = rnd.nextInt(4_000);
                        switch (rnd.nextInt(3)) {
                            case 0 -> p.add(k);
                            case 1 -> p.remove(k);
                            default -> p.contains(k);
                        }
                    }
                }, "autopilot-worker-" + t);
                workers[t].start();
            }
            for (int c = 0; c < 8; c++) {           // pilot cycles racing the workers
                p.pilotOnce();
                Thread.sleep(5);
            }
            for (Thread w : workers) {
                w.join();
            }
            // The invariant that matters: the set is still a strictly ascending ordered set.
            List<Integer> keys = p.set().inOrder();
            for (int i = 1; i < keys.size(); i++) {
                assertTrue(keys.get(i - 1) < keys.get(i), "order broken at " + i);
            }
            assertEquals(keys.size(), p.set().size());
            assertTrue(p.cycles() >= 8);
        }
    }

    @Test
    void cadenceMustBePositive() {
        OrderedSet<Integer> set = OrderedSet.withNaturalOrder(new RedBlackStrategy<Integer>());
        WorkloadAdaptation<Integer> a = WorkloadAdaptation.attach(set, MorphPolicy.defaults());
        assertThrows(IllegalArgumentException.class, () -> Autopilot.of(a, Duration.ZERO));
    }
}
