package io.github.richeyworks.superbeefsort.csrbt;

import io.github.richeyworks.csrbt.event.TreeEvent;
import io.github.richeyworks.csrbt.event.TreeEventListener;
import io.github.richeyworks.superbeefsort.core.SortEvent;
import io.github.richeyworks.superbeefsort.core.SortObserver;

import java.util.Objects;

/**
 * Forwards CSRBT's structured {@link TreeEvent}s onto SuperBeefSort's {@link SortObserver} stream as
 * {@link SortEvent.Type#TREE_EVENT}s — one observer, the whole pipeline: profile → select → sort →
 * feed → <em>morph / evict / repair / quarantine / heal / promote</em>. Until this bridge existed the
 * entire tree-side nervous system went unheard during a feed (CSRBT allocates no events without a
 * listener, so an unbridged feed produces literally zero tree telemetry).
 *
 * <p>Register via {@code OrderedSet.setEventListener(TreeEventBridge.lifecycle(observer))} or the
 * ensemble equivalent — {@code BeefSort} does this automatically for built targets when an observer
 * is set. Honors {@link TreeEventListener}'s fast/non-reentrant contract as long as the wrapped
 * observer does.</p>
 *
 * <p>Two verbosities: {@link #lifecycle} drops the per-key {@code Insert}/{@code Remove}/{@code Evict}
 * events (a bulk feed would otherwise flood the observer with one event per element) and forwards
 * only adaptation-level events; {@link #verbose} forwards everything, which is what a session
 * recorder or the arena visualizer wants.</p>
 */
public final class TreeEventBridge<K> implements TreeEventListener<K> {

    private final SortObserver observer;
    private final boolean perKeyEvents;

    private TreeEventBridge(SortObserver observer, boolean perKeyEvents) {
        this.observer = Objects.requireNonNull(observer, "observer");
        this.perKeyEvents = perKeyEvents;
    }

    /** Adaptation-level events only (morph/repair/quarantine/heal/promote/…); per-key events dropped. */
    public static <K> TreeEventBridge<K> lifecycle(SortObserver observer) {
        return new TreeEventBridge<>(observer, false);
    }

    /** Every event, per-key ones included — the full stream a recorder or visualizer feed wants. */
    public static <K> TreeEventBridge<K> verbose(SortObserver observer) {
        return new TreeEventBridge<>(observer, true);
    }

    @Override
    public void onEvent(TreeEvent<K> event) {
        boolean perKey = event instanceof TreeEvent.Insert
                || event instanceof TreeEvent.Remove
                || event instanceof TreeEvent.Evict;
        if (perKey && !perKeyEvents) {
            return;
        }
        // Hardening M-2: CSRBT invokes this listener on its write path, under its locks. A throwing
        // SortObserver must not be able to fail tree writes — observability never breaks the data
        // plane. (CSRBT's emit() now also swallows listener faults; this catch keeps the guarantee
        // even against CSRBT versions that don't.)
        try {
            observer.onEvent(SortEvent.of(SortEvent.Type.TREE_EVENT, render(event)));
        } catch (RuntimeException observerFault) {
            // dropped deliberately
        }
    }

    /**
     * One-line rendering. High-signal adaptation events get a shaped message; everything else falls
     * back to the record's own {@code toString()}, so new {@code TreeEvent} subtypes forward without
     * a bridge change (the hierarchy is sealed inside CSRBT's file, not here).
     */
    private String render(TreeEvent<K> e) {
        if (e instanceof TreeEvent.Morph<K> m) {
            return "morph " + m.fromStrategy() + " -> " + m.toStrategy()
                    + (m.committed() ? " (committed)" : " (rejected by health gate)");
        }
        if (e instanceof TreeEvent.Repair<K> r) {
            return "self-repair -> " + (r.healthy() ? "healthy" : "UNHEALTHY");
        }
        if (e instanceof TreeEvent.Promote<K> p) {
            return (p.failover() ? "failover " : "promote ") + p.fromMember() + " -> " + p.toMember();
        }
        if (e instanceof TreeEvent.Quarantine<K> q) {
            return "quarantine member " + q.member();
        }
        if (e instanceof TreeEvent.Heal<K> h) {
            return "heal member " + h.member() + " -> " + (h.healed() ? "healed" : "NOT healed");
        }
        if (e instanceof TreeEvent.Retire<K> r) {
            return "retire member " + r.member();
        }
        if (e instanceof TreeEvent.MemoryCeiling<K> m) {
            return "memory ceiling " + (m.breached() ? "BREACHED" : "cleared")
                    + " (estimate=" + m.estimateBytes() + "B, ceiling=" + m.ceilingBytes() + "B)";
        }
        return String.valueOf(e);
    }
}
