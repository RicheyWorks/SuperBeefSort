package io.github.richeyworks.superbeefsort.csrbt;

import io.github.richeyworks.csrbt.control.MorphPolicy;
import io.github.richeyworks.csrbt.control.RollingWorkloadMonitor;
import io.github.richeyworks.csrbt.control.WorkloadMonitor;
import io.github.richeyworks.csrbt.ensemble.EnsembleMember;
import io.github.richeyworks.csrbt.ensemble.EnsembleOrderedSet;
import io.github.richeyworks.csrbt.evolution.PolicyBandit;
import io.github.richeyworks.csrbt.evolution.PolicyEvolutionController;
import io.github.richeyworks.csrbt.evolution.PolicyGenome;
import io.github.richeyworks.csrbt.evolution.PolicySearchController;
import io.github.richeyworks.superbeefsort.core.SortObserver;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Wires a fed ensemble to CSRBT's <em>evolution machine</em> (ADR-011) — the third adaptation tier,
 * after {@link WorkloadAdaptation} (morph one tree) and {@link EnsembleAdaptation} (promote across
 * members): here the ensemble's non-primary members become <b>laboratories</b>, and CSRBT breeds,
 * trials, gate-kills, and promotes parameterized policies on them against the live stream.
 *
 * <p>Two flavors, mirroring CSRBT's two controllers:</p>
 * <ul>
 *   <li>{@link #banditSearch} — V3: a UCB1 {@link PolicyBandit} over the verified
 *       {@linkplain PolicyBandit#boxGrid() box grid} trials one arm at a time on one laboratory
 *       member ({@code beginCycle}/{@code endCycle} = one trial window).</li>
 *   <li>{@link #population} — V4: a (μ+λ) {@link PolicyEvolutionController} breeds founders into
 *       offspring materialized across a nursery of laboratory members
 *       ({@code beginCycle}/{@code endCycle} = one generation).</li>
 * </ul>
 *
 * <p>Like every controller in both codebases, this is <b>caller-cadenced</b>: route live traffic
 * through {@link #add}/{@link #remove}/{@link #contains}, open a cycle, keep routing, close the
 * cycle; the {@link MorphPolicy} gates decide whether a proven winner takes the throne (an O(1)
 * promotion). Deaths are real: an arm whose strategy fails its own invariant self-disqualifies
 * through the health gate, on the record. Wire {@link #observeWith(SortObserver)} to hear the
 * births, trials, culls, and promotions ({@code Trial}/{@code Lineage}/{@code Diversity} events via
 * {@link TreeEventBridge}) — the same stream CSRBT's arena visualizer replays.</p>
 *
 * <p><b>Honesty clause</b> (CSRBT's own ADR-011 verdict): searched parameters did <em>not</em> beat
 * the fixed strategy family in CSRBT's pre-registered experiment. This tier is for watching the
 * organism adapt on the record — selection, death, and promotion made observable — not a promised
 * speedup. The performance story remains the controller tiers below it.</p>
 *
 * <p>No CSRBT changes: composes the public controllers around the public ensemble; laboratory
 * members are ordinary non-primary, strategy-backed members (see
 * {@link EnsembleTargetFactory#evolutionHost}).</p>
 */
public final class EvolutionAdaptation<K> {

    /** Which evolution controller drives this adaptation. */
    public enum Mode { BANDIT_SEARCH, POPULATION }

    /**
     * One closed cycle, flattened to the shared shape of CSRBT's {@code TrialResult} /
     * {@code GenerationResult}: did a challenger take the throne, at what measured cost versus the
     * incumbent, and the controller's one-line reason.
     */
    public record CycleResult(Mode mode, boolean promoted, double challengerCost,
                              double incumbentCost, String reason) { }

    private final Mode mode;
    private final EnsembleOrderedSet<K> ensemble;
    private final WorkloadMonitor monitor;
    private final PolicySearchController<K> search;        // non-null iff BANDIT_SEARCH
    private final PolicyEvolutionController<K> evolution;  // non-null iff POPULATION

    private int opsSinceEval;
    private int cycles;
    private int promotions;

    private EvolutionAdaptation(Mode mode, EnsembleOrderedSet<K> ensemble, WorkloadMonitor monitor,
                                PolicySearchController<K> search, PolicyEvolutionController<K> evolution) {
        this.mode = mode;
        this.ensemble = ensemble;
        this.monitor = monitor;
        this.search = search;
        this.evolution = evolution;
    }

    /**
     * V3 bandit search over the verified {@linkplain PolicyBandit#boxGrid() box grid}: one arm per
     * cycle, trialed on the first non-primary strategy-backed member. The host should be an
     * all-exact-mode ensemble with at least one laboratory slot
     * ({@link EnsembleTargetFactory#evolutionHost} with {@code exactShadows=false} bulk-loads in O(n)/member).
     */
    public static <K> EvolutionAdaptation<K> banditSearch(EnsembleOrderedSet<K> ensemble, MorphPolicy policy) {
        return banditSearch(ensemble, new RollingWorkloadMonitor(), policy);
    }

    /** As {@link #banditSearch(EnsembleOrderedSet, MorphPolicy)} with a caller-supplied monitor. */
    public static <K> EvolutionAdaptation<K> banditSearch(EnsembleOrderedSet<K> ensemble,
                                                          WorkloadMonitor monitor, MorphPolicy policy) {
        Objects.requireNonNull(ensemble, "ensemble");
        Objects.requireNonNull(monitor, "monitor");
        Objects.requireNonNull(policy, "policy");
        EnsembleMember<K> trial = laboratories(ensemble).get(0);
        PolicySearchController<K> c = new PolicySearchController<>(
                ensemble, trial, monitor, new PolicyBandit(PolicyBandit.boxGrid()), policy);
        return new EvolutionAdaptation<>(Mode.BANDIT_SEARCH, ensemble, monitor, c, null);
    }

    /**
     * V4 (μ+λ) evolution: {@code founders} bred across every non-primary strategy-backed member (the
     * nursery, λ = its size; founders must fit it). The host should carry exact shadows
     * ({@link EnsembleTargetFactory#evolutionHost} with {@code exactShadows=true}, CSRBT's canonical
     * evolution setup). {@code allowOutOfBox=false} keeps mutation inside the verified box.
     */
    public static <K> EvolutionAdaptation<K> population(EnsembleOrderedSet<K> ensemble, MorphPolicy policy,
                                                        List<PolicyGenome> founders, int mu,
                                                        boolean allowOutOfBox, long seed) {
        return population(ensemble, new RollingWorkloadMonitor(), policy, founders, mu, allowOutOfBox, seed);
    }

    /** As {@link #population(EnsembleOrderedSet, MorphPolicy, List, int, boolean, long)} with a caller monitor. */
    public static <K> EvolutionAdaptation<K> population(EnsembleOrderedSet<K> ensemble, WorkloadMonitor monitor,
                                                        MorphPolicy policy, List<PolicyGenome> founders, int mu,
                                                        boolean allowOutOfBox, long seed) {
        Objects.requireNonNull(ensemble, "ensemble");
        Objects.requireNonNull(monitor, "monitor");
        Objects.requireNonNull(policy, "policy");
        PolicyEvolutionController<K> c = new PolicyEvolutionController<>(
                ensemble, laboratories(ensemble), monitor, policy, founders, mu, allowOutOfBox, seed);
        return new EvolutionAdaptation<>(Mode.POPULATION, ensemble, monitor, null, c);
    }

    /** Sensible default founders: the literature point WB(3,2) plus two in-box neighbors. */
    public static List<PolicyGenome> defaultFounders() {
        return List.of(PolicyGenome.weightBalanced(3, 2),
                       PolicyGenome.weightBalanced(4, 2),
                       PolicyGenome.weightBalanced(4, 3));
    }

    /** Every non-primary, strategy-backed member — the bodies evolution may re-shape. */
    private static <K> List<EnsembleMember<K>> laboratories(EnsembleOrderedSet<K> ensemble) {
        List<EnsembleMember<K>> labs = new ArrayList<>();
        for (EnsembleMember<K> m : ensemble.members()) {
            if (m != ensemble.primary() && m.isStrategyBacked()) {
                labs.add(m);
            }
        }
        if (labs.isEmpty()) {
            throw new IllegalArgumentException(
                    "evolution needs at least one non-primary strategy-backed member as a laboratory; "
                    + "build the host with EnsembleTargetFactory.evolutionHost(...)");
        }
        return labs;
    }

    // -- data plane: apply to the ensemble via the controller's facade (which feeds the monitor) --

    public boolean add(K key) {
        opsSinceEval++;
        return (search != null) ? search.add(key) : evolution.add(key);
    }

    public boolean remove(K key) {
        opsSinceEval++;
        return (search != null) ? search.remove(key) : evolution.remove(key);
    }

    public boolean contains(K key) {
        opsSinceEval++;
        return (search != null) ? search.contains(key) : evolution.contains(key);
    }

    // -- the cycle --

    /**
     * Open a cycle: the bandit picks one arm (singleton list), or a generation's population is bred
     * and materialized across the nursery. Route traffic, then {@link #endCycle()}.
     */
    public List<PolicyGenome> beginCycle() {
        return (search != null) ? List.of(search.beginTrial()) : evolution.beginGeneration();
    }

    /**
     * Close the cycle with the traffic recorded since it opened as the policy clock: score the
     * challenger(s) with real {@code Fitness} numbers, let the {@link MorphPolicy} gates decide
     * promotion, and report the verdict.
     */
    public CycleResult endCycle() {
        int elapsed = opsSinceEval;
        opsSinceEval = 0;
        cycles++;
        CycleResult result;
        if (search != null) {
            PolicySearchController.TrialResult r = search.endTrial(elapsed);
            result = new CycleResult(mode, r.promoted(), r.armCost(), r.incumbentCost(), r.reason());
        } else {
            PolicyEvolutionController.GenerationResult r = evolution.endGeneration(elapsed);
            result = new CycleResult(mode, r.promoted(), r.bestCost(), r.incumbentCost(), r.reason());
        }
        if (result.promoted()) {
            promotions++;
        }
        return result;
    }

    /**
     * Forward the controller's structured events — {@code Trial} arms/phases/costs, {@code Lineage}
     * births, {@code Diversity} — onto a {@link SortObserver} via a verbose {@link TreeEventBridge}:
     * the feed CSRBT's arena visualizer replays. {@code null} unregisters.
     */
    public void observeWith(SortObserver observer) {
        TreeEventBridge<K> bridge = (observer == null) ? null : TreeEventBridge.verbose(observer);
        if (search != null) {
            search.setEventListener(bridge);
        } else {
            evolution.setEventListener(bridge);
        }
    }

    // -- accessors --

    public Mode mode() { return mode; }

    /** The live host ensemble (its primary is the current throne). */
    public EnsembleOrderedSet<K> ensemble() { return ensemble; }

    /** The workload monitor — hand to {@code CsrbtTarget.observedBy(...)} so the feed is cycle-0 evidence. */
    public WorkloadMonitor monitor() { return monitor; }

    /** The raw V3 controller, or {@code null} in {@link Mode#POPULATION}. */
    public PolicySearchController<K> searchController() { return search; }

    /** The raw V4 controller, or {@code null} in {@link Mode#BANDIT_SEARCH}. */
    public PolicyEvolutionController<K> evolutionController() { return evolution; }

    public int cycles() { return cycles; }

    public int promotions() { return promotions; }
}
