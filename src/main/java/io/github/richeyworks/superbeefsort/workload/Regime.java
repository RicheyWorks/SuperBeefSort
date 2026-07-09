package io.github.richeyworks.superbeefsort.workload;

import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * One named traffic regime for a live CSRBT workload: how many operations, the read/write mix,
 * the write add-bias, where the keys come from, and (optionally) a sliding-window bound applied
 * when the regime begins. A driver (the aquarium, a demo, a test) interprets it per op:
 * with probability {@link #readFraction} do a lookup, otherwise a write that is an insert with
 * probability {@link #addBias} and a removal otherwise — keys drawn from {@link #keys} either way.
 *
 * <p>{@link #window}: {@code -1} leaves the target's window unchanged, {@code 0} unbounds it,
 * {@code > 0} bounds it at regime start (CSRBT FIFO-evicts the oldest-inserted key).</p>
 *
 * <p>Regimes are deliberately dumb data — all adaptation intelligence stays in CSRBT's control
 * plane; a regime just describes what the world is doing to the tree.</p>
 */
public record Regime(String name, int ops, double readFraction, double addBias,
                     IntSupplier keys, int window) {

    public Regime {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(keys, "keys");
        if (ops < 1) throw new IllegalArgumentException("ops must be >= 1: " + ops);
        if (readFraction < 0.0 || readFraction > 1.0) {
            throw new IllegalArgumentException("readFraction must be in [0,1]: " + readFraction);
        }
        if (addBias < 0.0 || addBias > 1.0) {
            throw new IllegalArgumentException("addBias must be in [0,1]: " + addBias);
        }
        if (window < -1) throw new IllegalArgumentException("window must be >= -1: " + window);
    }

    /** A regime that leaves the target's window untouched. */
    public static Regime of(String name, int ops, double readFraction, double addBias, IntSupplier keys) {
        return new Regime(name, ops, readFraction, addBias, keys, -1);
    }
}
