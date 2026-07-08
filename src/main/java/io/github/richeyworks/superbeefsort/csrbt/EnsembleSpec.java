package io.github.richeyworks.superbeefsort.csrbt;

import io.github.richeyworks.csrbt.ensemble.EnsembleMode;

import java.util.Objects;

/**
 * How {@link EnsembleTargetFactory} should compose an {@code EnsembleOrderedSet}'s members from the data
 * profile + access pattern (docs/architecture-csrbt-integration.md §4). Two ready mixes plus the full set
 * of CSRBT ensemble-builder knobs; the default is {@link #lean()}, which reproduces the historical member
 * set and MIRROR behavior exactly.
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
 *
 * <h2>The previously-unreachable knobs</h2>
 * <ul>
 *   <li>{@link #verified()} / {@link #verified(int)} — VERIFIED mode: reads are fanned out to a quorum
 *       and vote, with an optional verification stride. Requires ≥ 3 members, so pair it with
 *       {@link Mix#ADAPTIVE} or {@link #withSnapshot()}.</li>
 *   <li>{@link #sampledShadow(double)} / {@link #rebuildShadow(int)} — CSRBT's two write-lean shadow
 *       modes (ADR-003 Options B / C). Note the O(n) ensemble bulk build requires an all-exact mode
 *       (MIRROR/VERIFIED); shadow-mode targets are fed by median-first {@code add} instead — the
 *       feeders detect this automatically.</li>
 *   <li>{@link #withMemoryCeiling(long)} — CSRBT's estimated-footprint ceiling ({@code MemoryCeiling}
 *       events fire on breach; visible through {@code TreeEventBridge}).</li>
 *   <li>{@link #withMaxMembers(int)} — cap the member count.</li>
 *   <li>{@code largeNEngine} (default <b>on</b>) — when the profiled input is large
 *       ({@link EnsembleTargetFactory#LARGE_N_THRESHOLD}), add a page-structured B+tree engine member
 *       (ADR-008), CSRBT's large-n specialist, through the {@code RankedSet} seam. Disable with
 *       {@link #withoutLargeNEngine()} if the extra member's memory is unwanted.</li>
 * </ul>
 */
public record EnsembleSpec(Mix mix, boolean snapshot, EnsembleMode mode, int verifyEvery,
                           double shadowSampleRate, long memoryCeilingBytes, int rebuildEvery,
                           int maxMembers, boolean largeNEngine) {

    /** The member-composition recipe. */
    public enum Mix {
        /** Access-advised primary + Red-Black replica (the historical default). */
        LEAN,
        /** Red-Black + AVL + Splay — a promotable morph-family trio for read-path adaptation. */
        ADAPTIVE
    }

    public EnsembleSpec {
        Objects.requireNonNull(mix, "mix");
        Objects.requireNonNull(mode, "mode");
    }

    /** Historical two-knob form; every newer knob at its builder default (MIRROR, large-n engine on). */
    public EnsembleSpec(Mix mix, boolean snapshot) {
        this(mix, snapshot, EnsembleMode.MIRROR, 0, 0.0, 0L, 0, 0, true);
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
        return new EnsembleSpec(mix, true, mode, verifyEvery, shadowSampleRate, memoryCeilingBytes,
                rebuildEvery, maxMembers, largeNEngine);
    }

    /** VERIFIED mode: quorum-verified reads at the builder-default stride. Requires ≥ 3 members. */
    public EnsembleSpec verified() {
        return new EnsembleSpec(mix, snapshot, EnsembleMode.VERIFIED, verifyEvery, shadowSampleRate,
                memoryCeilingBytes, rebuildEvery, maxMembers, largeNEngine);
    }

    /** VERIFIED mode with an explicit verification stride ({@code verifyEvery} ≥ 1). */
    public EnsembleSpec verified(int stride) {
        if (stride < 1) throw new IllegalArgumentException("verifyEvery must be >= 1: " + stride);
        return new EnsembleSpec(mix, snapshot, EnsembleMode.VERIFIED, stride, shadowSampleRate,
                memoryCeilingBytes, rebuildEvery, maxMembers, largeNEngine);
    }

    /** SAMPLED_SHADOW mode (memory-lean, ADR-003 Option B) at write-sample rate {@code p} in (0, 1]. */
    public EnsembleSpec sampledShadow(double p) {
        if (p <= 0.0 || p > 1.0) throw new IllegalArgumentException("shadowSampleRate must be in (0,1]: " + p);
        return new EnsembleSpec(mix, snapshot, EnsembleMode.SAMPLED_SHADOW, verifyEvery, p,
                memoryCeilingBytes, rebuildEvery, maxMembers, largeNEngine);
    }

    /** REBUILD_SHADOW mode (write-lean, ADR-003 Option C), rebuilding shadows every {@code ops} writes. */
    public EnsembleSpec rebuildShadow(int ops) {
        if (ops < 1) throw new IllegalArgumentException("rebuildEvery must be >= 1: " + ops);
        return new EnsembleSpec(mix, snapshot, EnsembleMode.REBUILD_SHADOW, verifyEvery, shadowSampleRate,
                memoryCeilingBytes, ops, maxMembers, largeNEngine);
    }

    /** This spec plus CSRBT's estimated-footprint memory ceiling (bytes &gt; 0). */
    public EnsembleSpec withMemoryCeiling(long bytes) {
        if (bytes <= 0) throw new IllegalArgumentException("memoryCeilingBytes must be > 0: " + bytes);
        return new EnsembleSpec(mix, snapshot, mode, verifyEvery, shadowSampleRate, bytes,
                rebuildEvery, maxMembers, largeNEngine);
    }

    /** This spec with the member count capped at {@code k} ≥ 1. */
    public EnsembleSpec withMaxMembers(int k) {
        if (k < 1) throw new IllegalArgumentException("maxMembers must be >= 1: " + k);
        return new EnsembleSpec(mix, snapshot, mode, verifyEvery, shadowSampleRate, memoryCeilingBytes,
                rebuildEvery, k, largeNEngine);
    }

    /** This spec without the automatic large-n B+tree engine member. */
    public EnsembleSpec withoutLargeNEngine() {
        return new EnsembleSpec(mix, snapshot, mode, verifyEvery, shadowSampleRate, memoryCeilingBytes,
                rebuildEvery, maxMembers, false);
    }
}
