package io.github.richeyworks.superbeefsort.csrbt;

/**
 * How {@link EnsembleTargetFactory} should compose an {@code EnsembleOrderedSet}'s members from the data
 * profile + access pattern (docs/architecture-csrbt-integration.md §4). Two ready mixes plus an optional
 * snapshot member; the default is {@link #lean()}, which reproduces the historical member set exactly.
 *
 * <ul>
 *   <li>{@link Mix#LEAN} — the access-advised primary + a Red-Black replica: fault-tolerance and a
 *       differently-balanced second member at minimal K× cost. The right default for {@code buildEnsemble}.</li>
 *   <li>{@link Mix#ADAPTIVE} — the morph-family trio Red-Black + AVL + Splay, so CSRBT's
 *       {@code EnsembleController} can <em>promote</em> the read path to whichever member matches live
 *       traffic (balanced / read-heavy / skewed). The right default for {@code buildAdaptiveEnsemble}:
 *       a {@link Mix#LEAN} mix often carries only Red-Black members and so has nowhere to promote.</li>
 * </ul>
 *
 * <p>{@link #snapshot} adds an O(1)-snapshot persistent engine member (wait-free point-in-time reads). It
 * is never auto-promoted (it carries no {@code StrategyId}) but serves, votes, fails over, and heals — and
 * can be promoted explicitly when time-travel reads are needed.</p>
 */
public record EnsembleSpec(Mix mix, boolean snapshot) {

    /** The member-composition recipe. */
    public enum Mix {
        /** Access-advised primary + Red-Black replica (the historical default). */
        LEAN,
        /** Red-Black + AVL + Splay — a promotable morph-family trio for read-path adaptation. */
        ADAPTIVE
    }

    /** Access-advised primary + Red-Black replica; no snapshot. The {@code buildEnsemble} default. */
    public static EnsembleSpec lean() {
        return new EnsembleSpec(Mix.LEAN, false);
    }

    /** The promotable Red-Black + AVL + Splay trio; no snapshot. The {@code buildAdaptiveEnsemble} default. */
    public static EnsembleSpec adaptive() {
        return new EnsembleSpec(Mix.ADAPTIVE, false);
    }

    /** This spec plus an O(1)-snapshot persistent member (wait-free point-in-time reads). */
    public EnsembleSpec withSnapshot() {
        return new EnsembleSpec(mix, true);
    }
}
