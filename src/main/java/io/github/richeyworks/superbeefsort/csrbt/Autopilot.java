package io.github.richeyworks.superbeefsort.csrbt;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.control.MorphController;
import io.github.richeyworks.csrbt.ensemble.EnsembleController;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The self-driving mode: everything in CSRBT's control plane is deliberately caller-cadenced —
 * right for a library, but a service can't be the caller every few thousand ops. Autopilot wraps a
 * {@link WorkloadAdaptation} (morph tier) or {@link EnsembleAdaptation} (promotion tier) and runs
 * the cadence itself on a daemon scheduler: construct it, route traffic through it, forget it.
 *
 * <p><b>Why the facade, not just the timer.</b> CSRBT's monitors and controllers are
 * single-threaded by contract ({@code RollingWorkloadMonitor}: "not thread-safe by design").
 * Autopilot preserves that contract under concurrency by mutual exclusion: every data-plane op
 * ({@link #add}/{@link #remove}/{@link #contains}) and every pilot cycle synchronize on one lock,
 * so the monitor and controllers always observe single-threaded-equivalent execution — from any
 * number of caller threads. (The tree itself was already torn-read-free; the lock is for the
 * control plane's sketches, not the data.) The cost is one uncontended monitor acquisition per op
 * on top of {@code OrderedSet}'s own locking — noise against a tree walk.</p>
 *
 * <p>Each cycle runs one policy-gated evaluation ({@code maybeAdapt} / {@code maybePromote});
 * for ensembles, every {@code healthEvery}-th cycle also runs the failover/quarantine/heal
 * cadence. The gates keep autopilot exactly as anti-thrash as manual driving — this class adds
 * a clock, never an opinion. {@link #pilotOnce()} runs one cycle synchronously (what the
 * scheduler calls; also the deterministic seam for tests). {@link #close()} lands the plane.</p>
 */
public final class Autopilot<K> implements AutoCloseable {

    private final Object lock = new Object();
    private final WorkloadAdaptation<K> single;      // exactly one of these is non-null
    private final EnsembleAdaptation<K> ensemble;
    private final int healthEvery;
    private final ScheduledExecutorService scheduler;
    private final AtomicLong cycles = new AtomicLong();
    private volatile String lastVerdict = "not yet evaluated";

    private Autopilot(WorkloadAdaptation<K> single, EnsembleAdaptation<K> ensemble,
                      Duration cadence, int healthEvery) {
        this.single = single;
        this.ensemble = ensemble;
        this.healthEvery = healthEvery;
        Objects.requireNonNull(cadence, "cadence");
        if (cadence.isNegative() || cadence.isZero()) {
            throw new IllegalArgumentException("cadence must be positive: " + cadence);
        }
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "csrbt-autopilot");
            t.setDaemon(true);
            return t;
        });
        long ms = cadence.toMillis();
        scheduler.scheduleAtFixedRate(this::pilotOnce, ms, ms, TimeUnit.MILLISECONDS);
    }

    /** Fly a single adaptive set: one policy-gated morph evaluation per {@code cadence}. */
    public static <K> Autopilot<K> of(WorkloadAdaptation<K> adaptation, Duration cadence) {
        Objects.requireNonNull(adaptation, "adaptation");
        return new Autopilot<>(adaptation, null, cadence, 0);
    }

    /**
     * Fly an ensemble: one promotion evaluation per {@code cadence}, plus the health cadence
     * (failover/quarantine/heal) every {@code healthEvery}-th cycle ({@code 0} = never).
     */
    public static <K> Autopilot<K> of(EnsembleAdaptation<K> adaptation, Duration cadence, int healthEvery) {
        Objects.requireNonNull(adaptation, "adaptation");
        if (healthEvery < 0) throw new IllegalArgumentException("healthEvery must be >= 0: " + healthEvery);
        return new Autopilot<>(null, adaptation, cadence, healthEvery);
    }

    // ── Data plane: the thread-safe front door ───────────────────────────────────────────────

    public boolean add(K key) {
        synchronized (lock) {
            return single != null ? single.add(key) : ensemble.add(key);
        }
    }

    public boolean remove(K key) {
        synchronized (lock) {
            return single != null ? single.remove(key) : ensemble.remove(key);
        }
    }

    public boolean contains(K key) {
        synchronized (lock) {
            return single != null ? single.contains(key) : ensemble.contains(key);
        }
    }

    // ── The pilot ─────────────────────────────────────────────────────────────────────────────

    /**
     * One cycle, synchronously: evaluate (and possibly morph/promote), plus the ensemble health
     * cadence when due. Returns the one-line verdict. Safe to call from tests or manually
     * alongside the scheduler — everything serializes on the same lock.
     */
    public String pilotOnce() {
        synchronized (lock) {
            long n = cycles.incrementAndGet();
            String verdict;
            if (single != null) {
                MorphController.MorphResult r = single.maybeAdapt();
                verdict = r.morphed()
                        ? "morph " + r.from() + " -> " + r.to()
                        : "hold (" + r.reason() + ")";
            } else {
                EnsembleController.PromotionResult r = ensemble.maybePromote();
                verdict = r.promoted()
                        ? "promote " + r.from() + " -> " + r.to()
                        : "hold (" + r.reason() + ")";
                if (healthEvery > 0 && n % healthEvery == 0) {
                    EnsembleController.HealthReport h = ensemble.checkHealth();
                    if (h.changed()) {
                        verdict += "; health: failedOver=" + h.failedOver()
                                + " quarantined=" + h.quarantined()
                                + " healed=" + h.healed() + " retired=" + h.retired();
                    }
                }
            }
            lastVerdict = verdict;
            return verdict;
        }
    }

    // ── Instruments ───────────────────────────────────────────────────────────────────────────

    /** Pilot cycles flown so far (scheduled + manual). */
    public long cycles() { return cycles.get(); }

    /** The most recent cycle's one-line verdict. */
    public String lastVerdict() { return lastVerdict; }

    /** The single-set tier being flown, or {@code null} in ensemble mode. */
    public WorkloadAdaptation<K> adaptation() { return single; }

    /** The ensemble tier being flown, or {@code null} in single-set mode. */
    public EnsembleAdaptation<K> ensembleAdaptation() { return ensemble; }

    /** The live set (single-set mode) — reads on it are torn-read-free from any thread. */
    public OrderedSet<K> set() { return single != null ? single.set() : null; }

    /** Stop the scheduler. The wrapped adaptation stays usable (back to caller-cadenced). */
    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
