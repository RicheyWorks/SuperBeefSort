package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.TreeNode1;
import io.github.richeyworks.csrbt.control.MorphPolicy;
import io.github.richeyworks.csrbt.ensemble.EnsembleOrderedSet;
import io.github.richeyworks.csrbt.strategy.TreeStrategy;
import io.github.richeyworks.superbeefsort.core.ByteSequenceEncoder;
import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortObserver;
import io.github.richeyworks.superbeefsort.core.StepEventSink;
import io.github.richeyworks.superbeefsort.csrbt.AccessPolicy;
import io.github.richeyworks.superbeefsort.csrbt.EnsembleAdaptation;
import io.github.richeyworks.superbeefsort.csrbt.EnsembleSpec;
import io.github.richeyworks.superbeefsort.csrbt.EnsembleTargetFactory;
import io.github.richeyworks.superbeefsort.csrbt.EvolutionAdaptation;
import io.github.richeyworks.superbeefsort.csrbt.ProfileGuidedScorer;
import io.github.richeyworks.superbeefsort.csrbt.StrategyAdvisor;
import io.github.richeyworks.superbeefsort.csrbt.TreeEventBridge;
import io.github.richeyworks.superbeefsort.csrbt.WorkloadAdaptation;
import io.github.richeyworks.superbeefsort.engine.BeefSortEngine;
import io.github.richeyworks.superbeefsort.engine.JobSpec;
import io.github.richeyworks.superbeefsort.engine.SortRunResult;
import io.github.richeyworks.superbeefsort.external.ExternalMergeSorter;
import io.github.richeyworks.superbeefsort.external.ExternalSortResult;
import io.github.richeyworks.superbeefsort.external.SpillSerializer;
import io.github.richeyworks.superbeefsort.feed.CsrbtTarget;
import io.github.richeyworks.superbeefsort.feed.FeedMode;
import io.github.richeyworks.superbeefsort.feed.FeedResult;
import io.github.richeyworks.superbeefsort.feed.HealthPolicy;
import io.github.richeyworks.superbeefsort.feed.ParallelFeeder;
import io.github.richeyworks.superbeefsort.feed.StreamingFeeder;
import io.github.richeyworks.superbeefsort.select.CostModelStrategySelector;
import io.github.richeyworks.superbeefsort.select.SelectionPolicy;
import io.github.richeyworks.superbeefsort.select.StrategySelector;
import io.github.richeyworks.superbeefsort.strategy.MsdRadixSortStrategy;
import io.github.richeyworks.superbeefsort.stream.AdaptiveStreamSorter;
import io.github.richeyworks.superbeefsort.stream.DriftDetector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.Supplier;

/**
 * Fluent front door to SuperBeefSort. Supplying a {@link KeyEncoder} lets the engine choose
 * non-comparison sorts (counting / radix) when the profiler confirms the keys support it:
 *
 * <pre>{@code
 * OrderedSet<Integer> set = OrderedSet.withNaturalOrder(new RedBlackStrategy<Integer>());
 * BeefSort.with(Comparator.<Integer>naturalOrder())
 *         .source(data)
 *         .keyEncoder(KeyEncoder.ofInt(i -> i))   // unlocks radix / counting
 *         .policy(SelectionPolicy.SMART)
 *         .feedInto(set);
 * }</pre>
 *
 * <p>To have SuperBeefSort <em>construct</em> the CSRBT target (O(n), zero-rotation, born with the
 * profile-advised balancing strategy) instead of feeding an existing one, use {@link #buildOrderedSet()} or
 * {@link #buildEnsemble()} with {@link #accessPattern(AccessPolicy)} — see
 * {@code docs/architecture-csrbt-integration.md}.</p>
 */
public final class BeefSort<K> {

    private final Comparator<? super K> comparator;
    private List<K> source;
    private SelectionPolicy policy = SelectionPolicy.SMART;
    private FeedMode feedMode; // null -> the plan decides
    private SortObserver observer = SortObserver.NOOP;
    private KeyEncoder<K> keyEncoder;             // null -> comparison sorts only
    private ByteSequenceEncoder<K> byteSequenceEncoder; // null -> no MSD radix auto-selection
    private StrategySelector selector; // null -> engine default (rule-based)
    private OptionalLong maxAuxMemory = OptionalLong.empty(); // present -> cap SMART aux memory (via cost model)
    private OptionalLong randomSeed = OptionalLong.empty(); // present -> deterministic, reproducible runs
    private AccessPolicy accessPolicy = AccessPolicy.BALANCED; // drives StrategyAdvisor for build*()
    private Supplier<? extends TreeStrategy<K>> targetStrategy; // null -> StrategyAdvisor decides
    private HealthPolicy healthPolicy = HealthPolicy.defaults(); // streaming batch size + self-heal cadence
    private StepEventSink stepEventSink; // null -> step events disabled (default)
    private TreeNode1.Augmentor<K> augmentor; // null -> CSRBT's default subtree-size augmentation

    private BeefSort(Comparator<? super K> comparator) {
        this.comparator = comparator;
    }

    public static <K> BeefSort<K> with(Comparator<? super K> comparator) {
        return new BeefSort<>(comparator);
    }

    public BeefSort<K> source(List<K> data) {
        this.source = data;
        return this;
    }

    public BeefSort<K> policy(SelectionPolicy p) {
        this.policy = p;
        return this;
    }

    public BeefSort<K> feedMode(FeedMode m) {
        this.feedMode = m;
        return this;
    }

    public BeefSort<K> observe(SortObserver o) {
        this.observer = o;
        return this;
    }

    /** Supply an order-preserving integer encoding to enable non-comparison sorts. */
    public BeefSort<K> keyEncoder(KeyEncoder<K> encoder) {
        this.keyEncoder = encoder;
        return this;
    }

    /**
     * Supply a byte-sequence view of the keys (see {@link ByteSequenceEncoder#forStrings()}) to enable
     * automatic MSD radix selection — the engine will profile, detect the encoder, and route to
     * {@code radix.msd} without requiring an explicit {@link #sortByteKeys} call.
     */
    public BeefSort<K> byteSequenceEncoder(ByteSequenceEncoder<K> enc) {
        this.byteSequenceEncoder = enc;
        return this;
    }

    /** Override the strategy selector (e.g. a {@code CostModelStrategySelector}). */
    public BeefSort<K> selector(StrategySelector strategySelector) {
        this.selector = strategySelector;
        return this;
    }

    /**
     * Cap the auxiliary memory (in bytes) a {@link SelectionPolicy#SMART} selection may use: strategies whose
     * estimated peak aux space exceeds {@code maxBytes} are not chosen, so selection degrades to in-place
     * sorts under memory pressure. Convenience for {@code selector(new CostModelStrategySelector(maxBytes))} —
     * it installs a budgeted cost-model selector, so it is mutually exclusive with an explicit
     * {@link #selector(StrategySelector)} (to budget a different selector, e.g. the bandit, construct it with
     * the budget and pass it to {@code selector} instead). Affects {@code SMART} only; {@code STABLE} keeps
     * its own merge→WikiSort crossover.
     */
    public BeefSort<K> maxAuxMemory(long maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxAuxMemory must be positive: " + maxBytes);
        }
        this.maxAuxMemory = OptionalLong.of(maxBytes);
        return this;
    }

    /** Deterministic mode: randomized strategies (e.g. quicksort's pivot) seed from {@code seed}, so the run is reproducible. */
    public BeefSort<K> deterministic(long seed) {
        this.randomSeed = OptionalLong.of(seed);
        return this;
    }

    /** Declare the expected access pattern; {@link #buildOrderedSet()} / {@link #buildEnsemble()} pick the CSRBT strategy from it. */
    public BeefSort<K> accessPattern(AccessPolicy p) {
        this.accessPolicy = p == null ? AccessPolicy.BALANCED : p;
        return this;
    }

    /** Override the advised CSRBT balancing strategy for {@link #buildOrderedSet()}. */
    public BeefSort<K> targetStrategy(Supplier<? extends TreeStrategy<K>> strategy) {
        this.targetStrategy = strategy;
        return this;
    }

    /** Health/backpressure policy for the streaming feeder (batch size + self-heal cadence). */
    public BeefSort<K> withHealthPolicy(HealthPolicy policy) {
        this.healthPolicy = (policy == null) ? HealthPolicy.defaults() : policy;
        return this;
    }

    /**
     * Enable per-step event recording: every comparison, swap, and indexed element move during
     * the sort is sent to {@code sink} as a {@link io.github.richeyworks.superbeefsort.core.StepEvent}.
     * When this method is NOT called (the default), the buffer emits nothing — zero allocation and
     * zero extra work on the hot path.
     */
    public BeefSort<K> stepEvents(StepEventSink sink) {
        this.stepEventSink = sink;
        return this;
    }

    /**
     * Sort, then <em>construct</em> a CSRBT {@link OrderedSet} directly from the sorted run in O(n) with no
     * rotations (via {@code OrderedSet.fromSorted}), born with the {@link #accessPattern}-advised (or
     * {@link #targetStrategy}-overridden) balancing strategy. The "construct, don't insert" path — see
     * docs/architecture-csrbt-integration.md.
     */
    public OrderedSet<K> buildOrderedSet() {
        SortRunResult<K> run = engine().sort(source, comparator, spec());
        List<K> distinct = distinct(run.sorted());
        TreeStrategy<K> strategy = (targetStrategy != null)
                ? targetStrategy.get()
                : StrategyAdvisor.advise(run.profile(), accessPolicy);
        return finishSet(OrderedSet.fromSorted(distinct, strategy, comparator));
    }

    /**
     * Install a CSRBT per-node {@linkplain TreeNode1.Augmentor augmentor} on every {@code build*()}
     * OrderedSet target, so the tree is <em>born augmented</em> (e.g. interval max-hi via CSRBT's
     * {@code IntervalAugmentor}): the O(n) bulk build plus one re-augment pass, instead of the caller
     * retro-fitting augmentation onto a built set. Augmented data survives CSRBT morphs/self-repairs
     * (per-node tags carry across). {@code null} keeps CSRBT's default subtree-size augmentation.
     */
    public BeefSort<K> augmentor(TreeNode1.Augmentor<K> a) {
        this.augmentor = a;
        return this;
    }

    /**
     * Post-construction wiring every built {@link OrderedSet} gets: the declared {@link #augmentor},
     * and — when an {@link #observe observer} is registered — a {@link TreeEventBridge} so CSRBT's
     * structured adaptation events (morph/evict/repair/…) speak on the same stream as the sort's
     * lifecycle events. With no observer the listener stays unset and CSRBT's write path remains
     * allocation-free for events.
     */
    private OrderedSet<K> finishSet(OrderedSet<K> set) {
        if (augmentor != null) {
            set.setAugmentor(augmentor);
        }
        if (observer != SortObserver.NOOP) {
            set.setEventListener(TreeEventBridge.lifecycle(observer));
        }
        return set;
    }

    /**
     * Gap 12: as {@link #buildOrderedSet()} but returned through CSRBT's {@code NavigableSet}
     * adapter — a drop-in replacement for {@code TreeSet} call sites, built in O(n) from the
     * sorted run and born with the profile-advised strategy. The adapter is a live view: order
     * statistics remain reachable via the underlying set if you keep a reference.
     */
    public java.util.NavigableSet<K> buildNavigableSet() {
        return new io.github.richeyworks.csrbt.adapter.NavigableOrderedSet<>(buildOrderedSet());
    }

    /**
     * Gap 12: the persistence handoff — sort, construct the born-optimal {@link OrderedSet} (as
     * {@link #buildOrderedSet()}), then persist it as a named CSRBT snapshot via
     * {@link io.github.richeyworks.csrbt.persistence.FilePersistenceAdapter} (written under the
     * adapter's {@code snapshots/} directory, name traversal-guarded by CSRBT). The sorted-run →
     * balanced-tree → durable-snapshot pipeline in one call; reload later with
     * {@code loadOrderedSet(name, keySerializer, comparator)} — loads are health-gated, so a
     * corrupted snapshot is refused rather than served.
     */
    public OrderedSet<K> buildOrderedSetPersisted(String snapshotName,
                                                  io.github.richeyworks.csrbt.persistence.KeySerializer<K> keySerializer) {
        Objects.requireNonNull(snapshotName, "snapshotName");
        Objects.requireNonNull(keySerializer, "keySerializer");
        OrderedSet<K> set = buildOrderedSet();
        new io.github.richeyworks.csrbt.persistence.FilePersistenceAdapter()
                .saveSnapshot(snapshotName, set, keySerializer);
        return set;
    }

    /**
     * Sort, then <em>construct and bulk-load</em> a CSRBT {@link EnsembleOrderedSet} composed from the profile
     * via the O(n)/member parallel bulk path — the default {@link EnsembleSpec#lean()} mix (access-advised
     * primary + a RedBlack replica). The "ensemble as a first-class target" pattern, docs §4.
     */
    public EnsembleOrderedSet<K> buildEnsemble() {
        return buildEnsemble(EnsembleSpec.lean());
    }

    /**
     * As {@link #buildEnsemble()} but with an explicit {@link EnsembleSpec} member mix — e.g.
     * {@link EnsembleSpec#adaptive()} for a promotable RedBlack+AVL+Splay trio, or {@code .withSnapshot()} to
     * add an O(1)-snapshot persistent member for time-travel reads.
     */
    public EnsembleOrderedSet<K> buildEnsemble(EnsembleSpec ensembleSpec) {
        SortRunResult<K> run = engine().sort(source, comparator, spec());
        EnsembleOrderedSet<K> ensemble =
                EnsembleTargetFactory.forProfile(run.profile(), accessPolicy, comparator, ensembleSpec);
        if (observer != SortObserver.NOOP) {
            ensemble.setEventListener(TreeEventBridge.lifecycle(observer));
        }
        new ParallelFeeder<K>().feed(run.sorted(), CsrbtTarget.of(ensemble));
        return ensemble;
    }

    /**
     * Sort, build a <em>promotable</em> ensemble — the {@link EnsembleSpec#adaptive()} RedBlack+AVL+Splay mix
     * by default, so the read path has somewhere to migrate — then wire it to CSRBT's ensemble control plane:
     * the returned {@link EnsembleAdaptation} lets you report live operations and have the read path
     * <em>promote</em> to whichever member matches the workload — an O(1) primary swap, no rebuild (docs §4).
     * The single-set {@link #buildAdaptive(MorphPolicy)} morphs one tree; this migrates across pre-built members.
     */
    public EnsembleAdaptation<K> buildAdaptiveEnsemble(MorphPolicy policy) {
        return buildAdaptiveEnsemble(EnsembleSpec.adaptive(), policy);
    }

    /**
     * As {@link #buildAdaptiveEnsemble(MorphPolicy)} but with an explicit {@link EnsembleSpec} member mix.
     * The adaptation is attached <em>before</em> the bulk feed and the feed is routed through its monitor
     * ({@code CsrbtTarget.observedBy}), so the load itself is the first workload the promotion scorer sees.
     */
    public EnsembleAdaptation<K> buildAdaptiveEnsemble(EnsembleSpec ensembleSpec, MorphPolicy policy) {
        SortRunResult<K> run = engine().sort(source, comparator, spec());
        EnsembleOrderedSet<K> ensemble =
                EnsembleTargetFactory.forProfile(run.profile(), accessPolicy, comparator, ensembleSpec);
        if (observer != SortObserver.NOOP) {
            ensemble.setEventListener(TreeEventBridge.lifecycle(observer));
        }
        EnsembleAdaptation<K> adaptation = EnsembleAdaptation.attach(ensemble, policy);
        new ParallelFeeder<K>().feed(run.sorted(), CsrbtTarget.of(ensemble).observedBy(adaptation.monitor()));
        return adaptation;
    }

    /**
     * The ensemble analog of {@link #buildCoOptimized(MorphPolicy)}: sort, build a <em>promotable</em>
     * ensemble (the {@link EnsembleSpec#adaptive()} RedBlack+AVL+Splay mix by default), bulk-load it, then
     * wire it to CSRBT's ensemble control plane with a {@link ProfileGuidedScorer} prior whose
     * <em>strength</em> is {@linkplain ProfileGuidedScorer#derivePrior derived from the realized run} — so the
     * read path starts migrating toward the profile-favored member, harder when the sort was clean/cheap and
     * exactly measured, before live traffic has fully spoken. Where {@link #buildAdaptiveEnsemble(MorphPolicy)}
     * attaches an unbiased controller, this primes promotion with the sort's measured signal (docs §4 / Gap 5).
     */
    public EnsembleAdaptation<K> buildCoOptimizedEnsemble(MorphPolicy policy) {
        return buildCoOptimizedEnsemble(EnsembleSpec.adaptive(), policy);
    }

    /** As {@link #buildCoOptimizedEnsemble(MorphPolicy)} but with an explicit {@link EnsembleSpec} member mix. */
    public EnsembleAdaptation<K> buildCoOptimizedEnsemble(EnsembleSpec ensembleSpec, MorphPolicy policy) {
        SortRunResult<K> run = engine().sort(source, comparator, spec());
        EnsembleOrderedSet<K> ensemble =
                EnsembleTargetFactory.forProfile(run.profile(), accessPolicy, comparator, ensembleSpec);
        if (observer != SortObserver.NOOP) {
            ensemble.setEventListener(TreeEventBridge.lifecycle(observer));
        }
        EnsembleAdaptation<K> adaptation =
                EnsembleAdaptation.attachProfileGuided(ensemble, run.profile(), accessPolicy, run.sortMetrics(), policy);
        new ParallelFeeder<K>().feed(run.sorted(), CsrbtTarget.of(ensemble).observedBy(adaptation.monitor()));
        return adaptation;
    }

    /**
     * The third adaptation tier — feed CSRBT's <em>evolution machine</em> (ADR-011 V3): sort, build an
     * evolution host (access-advised primary on the throne + one Red-Black laboratory member), bulk-load
     * every member in O(n), route the feed into the evolution monitor as cycle-0 evidence, and return an
     * {@link EvolutionAdaptation} whose UCB1 bandit trials verified-box {@code WB(Δ,Γ)} genomes live on
     * the laboratory — gate-killed when unsound, promoted through the {@link MorphPolicy} gates when
     * proven. Drive it caller-cadenced: traffic through the adaptation, {@code beginCycle()} /
     * {@code endCycle()} per window; {@code observeWith(observer)} streams the Trial events. See
     * {@link EvolutionAdaptation}'s honesty clause: this tier is selection made observable, not a
     * promised speedup.
     */
    public EvolutionAdaptation<K> buildEvolvingEnsemble(MorphPolicy morphPolicy) {
        SortRunResult<K> run = engine().sort(source, comparator, spec());
        EnsembleOrderedSet<K> host = EnsembleTargetFactory.evolutionHost(
                run.profile(), accessPolicy, comparator, 1, false);   // MIRROR: O(n)/member bulk load
        if (observer != SortObserver.NOOP) {
            host.setEventListener(TreeEventBridge.lifecycle(observer));
        }
        EvolutionAdaptation<K> evolution = EvolutionAdaptation.banditSearch(host, morphPolicy);
        if (observer != SortObserver.NOOP) {
            evolution.observeWith(observer);   // Trial arms/phases/costs on the same stream
        }
        new ParallelFeeder<K>().feed(run.sorted(), CsrbtTarget.of(host).observedBy(evolution.monitor()));
        return evolution;
    }

    /**
     * As {@link #buildEvolvingEnsemble(MorphPolicy)} but the full (μ+λ) machine (ADR-011 V4): a nursery
     * of {@code lambda} laboratory members carrying exact shadows (CSRBT's canonical evolution setup —
     * every write reaches the nursery, reads never fan out to it), {@code founders} bred with bounded
     * in-box mutation and blend, μ survivors per generation, deaths and births on the record
     * ({@code Lineage}/{@code Trial}/{@code Diversity} via {@code observeWith}). Shadow mode means the
     * load feeds median-first rather than O(n)-bulk — the feeders detect that automatically. Use
     * {@link EvolutionAdaptation#defaultFounders()} when you have no opinion; {@code founders.size()}
     * must fit {@code lambda}.
     */
    public EvolutionAdaptation<K> buildEvolvingEnsemble(MorphPolicy morphPolicy, List<io.github.richeyworks.csrbt.evolution.PolicyGenome> founders,
                                                        int mu, int lambda, long seed) {
        SortRunResult<K> run = engine().sort(source, comparator, spec());
        EnsembleOrderedSet<K> host = EnsembleTargetFactory.evolutionHost(
                run.profile(), accessPolicy, comparator, lambda, true);   // exact shadows: the nursery
        if (observer != SortObserver.NOOP) {
            host.setEventListener(TreeEventBridge.lifecycle(observer));
        }
        EvolutionAdaptation<K> evolution =
                EvolutionAdaptation.population(host, morphPolicy, founders, mu, false, seed);
        if (observer != SortObserver.NOOP) {
            evolution.observeWith(observer);
        }
        new ParallelFeeder<K>().feed(run.sorted(), CsrbtTarget.of(host).observedBy(evolution.monitor()));
        return evolution;
    }

    /**
     * Sort, construct the born-optimal {@link OrderedSet} (as {@link #buildOrderedSet()}), then wire it to
     * CSRBT's self-adaptive control plane under {@code policy}: the returned {@link WorkloadAdaptation} lets
     * you report live operations and have the tree re-tune its strategy to the observed workload — "born
     * optimal AND wired to adapt" (docs/architecture-csrbt-integration.md §5). The access pattern must map
     * to a morph-managed strategy (BALANCED/READ_HEAVY/SKEWED, or an RB/AVL/Splay/Hybrid targetStrategy).
     */
    public WorkloadAdaptation<K> buildAdaptive(MorphPolicy policy) {
        SortRunResult<K> run = engine().sort(source, comparator, spec());
        List<K> distinct = distinct(run.sorted());
        TreeStrategy<K> strategy = (targetStrategy != null)
                ? targetStrategy.get()
                : StrategyAdvisor.advise(run.profile(), accessPolicy);
        OrderedSet<K> set = finishSet(OrderedSet.fromSorted(distinct, strategy, comparator));
        WorkloadAdaptation<K> adaptation = WorkloadAdaptation.attach(set, policy);
        adaptation.recordFeed(distinct);   // the load is the first workload the scorer sees
        return adaptation;
    }

    /**
     * Sort, build a born-optimal {@link OrderedSet} in the morph-family strategy the data profile favors,
     * then wire it to CSRBT's control plane with a {@link ProfileGuidedScorer} prior toward that strategy —
     * "co-optimization": the sort's profile both shapes the tree at birth and primes its adaptation, which
     * then defers to the live workload (docs/architecture-csrbt-integration.md §5). The prior's
     * <em>strength</em> is {@linkplain ProfileGuidedScorer#derivePrior derived from the realized run}
     * (Gap&nbsp;5): a clean, cheap, exactly-measured sort nudges harder toward the favored strategy, an
     * expensive or only-sampled one more softly. Unlike
     * {@link #buildAdaptive(MorphPolicy)} this always uses a morph-managed strategy (so WRITE_HEAVY maps to
     * Red-Black here, not the static weight-balanced shape), guaranteeing the tree can adapt.
     */
    public WorkloadAdaptation<K> buildCoOptimized(MorphPolicy policy) {
        SortRunResult<K> run = engine().sort(source, comparator, spec());
        List<K> distinct = distinct(run.sorted());
        var favored = ProfileGuidedScorer.favoredStrategy(run.profile(), accessPolicy);
        TreeStrategy<K> born = (targetStrategy != null) ? targetStrategy.get() : favored.<K>newStrategy();
        OrderedSet<K> set = finishSet(OrderedSet.fromSorted(distinct, born, comparator));
        WorkloadAdaptation<K> adaptation =
                WorkloadAdaptation.attachProfileGuided(set, run.profile(), accessPolicy, run.sortMetrics(), policy);
        adaptation.recordFeed(distinct);   // the load is the first workload the scorer sees
        return adaptation;
    }

    /**
     * Sort, then <em>stream</em> the run into a bounded {@code target}: SuperBeefSort sets the window
     * ({@code maxSize}) and feeds in {@link #withHealthPolicy(HealthPolicy) policy}-sized batches with
     * self-heal backpressure. Because the run is ascending and CSRBT FIFO-evicts the oldest-inserted key,
     * the target converges to the largest {@code maxSize} distinct keys — a sliding-window / top-N target.
     * {@code maxSize <= 0} streams unbounded. The streaming / bounded path — docs/architecture-csrbt-integration.md §2.3.
     */
    public FeedResult streaming(OrderedSet<K> target, int maxSize) {
        SortRunResult<K> run = engine().sort(source, comparator, spec());
        return new StreamingFeeder<K>(maxSize, healthPolicy).feed(run.sorted(), CsrbtTarget.of(target));
    }

    /**
     * Build a drift-aware {@link AdaptiveStreamSorter} that streams successive <em>batches</em> into
     * {@code target} (bounded to {@code maxSize}; {@code <= 0} unbounded), re-selecting the sort strategy
     * only when the data distribution drifts — the sort-side mirror of CSRBT's morph controller. Reuses
     * this builder's comparator, key encoder, selector, policy, observer, and {@link #withHealthPolicy
     * health policy}; {@link #source(List)} is ignored (feed batches via
     * {@link AdaptiveStreamSorter#accept(List)}). Uses the default {@link DriftDetector}; see
     * {@link #adaptiveStream(OrderedSet, int, DriftDetector)} to tune threshold/warmup/cooldown.
     */
    public AdaptiveStreamSorter<K> adaptiveStream(OrderedSet<K> target, int maxSize) {
        return adaptiveStream(target, maxSize, new DriftDetector());
    }

    /** As {@link #adaptiveStream(OrderedSet, int)} but with a caller-supplied {@link DriftDetector}. */
    public AdaptiveStreamSorter<K> adaptiveStream(OrderedSet<K> target, int maxSize, DriftDetector detector) {
        return AdaptiveStreamSorter.<K>builder(comparator)
                .keyEncoder(keyEncoder)
                .byteSequenceEncoder(byteSequenceEncoder)
                .selector(resolveSelector())
                .policy(policy)
                .detector(detector)
                .healthPolicy(healthPolicy)
                .observe(observer)
                .into(target, maxSize);
    }

    /**
     * The fully-coupled stream: as {@link #adaptiveStream(OrderedSet, int)}, but drift reaches
     * <em>both</em> engines. The sorter streams into {@code adaptation.set()}, the feed traffic is
     * folded into the adaptation's workload monitor, and every fired drift verdict hands the tree one
     * policy-gated {@code maybeAdapt()} — the sort re-tunes to the data distribution while the tree
     * re-tunes to the same regime shift, each behind its own anti-thrash gates (docs §2.3 + §5,
     * finally talking to each other).
     */
    public AdaptiveStreamSorter<K> adaptiveStream(WorkloadAdaptation<K> adaptation, int maxSize) {
        return AdaptiveStreamSorter.<K>builder(comparator)
                .keyEncoder(keyEncoder)
                .byteSequenceEncoder(byteSequenceEncoder)
                .selector(resolveSelector())
                .policy(policy)
                .detector(new DriftDetector())
                .healthPolicy(healthPolicy)
                .observe(observer)
                .adaptTree(adaptation)
                .into(adaptation.set(), maxSize);
    }

    /**
     * MSD-radix-sort the {@link #source(List) source} by a {@link ByteSequenceEncoder} view of the keys
     * (strings, byte arrays) and return the sorted list — the variable-length-key path the {@code long}-keyed
     * counting / LSD-radix sorts can't serve. The encoder must be faithful to this builder's comparator
     * (see {@link ByteSequenceEncoder#forStrings()}). Stable.
     */
    public List<K> sortByteKeys(ByteSequenceEncoder<K> encoder) {
        SortBuffer<K> buffer = SortBuffer.of(source, comparator, keyEncoder);
        new MsdRadixSortStrategy<K>(encoder).sort(buffer, new SortContext(observer, randomSeed));
        return buffer.toList();
    }

    /** Sort only. */
    public SortRunResult<K> run() {
        return engine().sort(source, comparator, spec());
    }

    /** Sort and feed into an {@link OrderedSet}. */
    public SortRunResult<K> feedInto(OrderedSet<K> set) {
        return engine().sortAndFeed(source, CsrbtTarget.of(set), spec());
    }

    /** Sort and feed into an {@link EnsembleOrderedSet}. */
    public SortRunResult<K> feedInto(EnsembleOrderedSet<K> ensemble) {
        return engine().sortAndFeed(source, CsrbtTarget.of(ensemble), spec());
    }

    private BeefSortEngine<K> engine() {
        StrategySelector sel = resolveSelector();
        return sel == null
                ? new BeefSortEngine<>(keyEncoder, byteSequenceEncoder)
                : new BeefSortEngine<>(sel, keyEncoder, byteSequenceEncoder);
    }

    /**
     * The effective selector: a budgeted {@link CostModelStrategySelector} when {@link #maxAuxMemory} is set,
     * otherwise the explicit {@link #selector} (possibly {@code null} → engine default). The two are mutually
     * exclusive, since a budget installs its own selector.
     */
    private StrategySelector resolveSelector() {
        if (maxAuxMemory.isPresent()) {
            if (selector != null) {
                throw new IllegalStateException(
                        "maxAuxMemory(...) and selector(...) are mutually exclusive; "
                                + "set the budget on your selector instead");
            }
            return new CostModelStrategySelector(maxAuxMemory.getAsLong());
        }
        return selector;
    }

    /** Linear de-dup of an already-ascending run under {@link #comparator}. */
    private List<K> distinct(List<K> sorted) {
        List<K> out = new ArrayList<>(sorted.size());
        for (K k : sorted) {
            if (out.isEmpty() || comparator.compare(out.get(out.size() - 1), k) != 0) {
                out.add(k);
            }
        }
        return out;
    }

    private JobSpec spec() {
        JobSpec s = JobSpec.defaults().withPolicy(policy).withObserver(observer);
        if (feedMode != null) {
            s = s.withFeedMode(feedMode);
        }
        if (randomSeed.isPresent()) {
            s = s.withRandomSeed(randomSeed.getAsLong());
        }
        if (stepEventSink != null) {
            s = s.withStepEvents(stepEventSink);
        }
        return s;
    }

    // ---- external merge sort ----

    /**
     * Begin configuring an external merge sort that handles inputs larger than available RAM: the
     * in-memory engine sorts fixed-size chunks (profile → select → sort), spills sorted runs to
     * temp files, and a tournament-tree k-way merge produces a globally sorted stream. Temp files
     * are registered for deletion-on-JVM-exit and are explicitly deleted once consumed.
     *
     * <p>Example — sort 10M longs that don't fit in the default heap:
     * <pre>{@code
     * List<Long> sorted = BeefSort.with(Comparator.<Long>naturalOrder())
     *         .keyEncoder(KeyEncoder.ofLong(x -> x))
     *         .source(bigList)
     *         .external(SpillSerializer.forLongs())
     *         .runSize(500_000)
     *         .fanIn(16)
     *         .toList();
     * }</pre>
     *
     * @param serializer Compact element serializer for the spill files. Use
     *                   {@link SpillSerializer#forLongs()}, {@link SpillSerializer#forIntegers()},
     *                   or {@link SpillSerializer#forStrings()} for the common types; supply a
     *                   custom one for other element types.
     * @throws IllegalStateException if {@link #source(List)} has not been called.
     */
    public ExternalSortBuilder external(SpillSerializer<K> serializer) {
        if (source == null) {
            throw new IllegalStateException("source not set — call source(list) before external()");
        }
        return new ExternalSortBuilder(Objects.requireNonNull(serializer, "serializer"));
    }

    /**
     * Fluent configuration for an external merge sort over this builder's source, comparator,
     * selector, and policy. Chain {@link #runSize} / {@link #fanIn} then call a terminal method.
     */
    public final class ExternalSortBuilder {

        private final SpillSerializer<K> serializer;
        private int runSize = 100_000;
        private int fanIn = 16;
        private java.nio.file.Path spillDir;   // null = system temp dir

        private ExternalSortBuilder(SpillSerializer<K> serializer) {
            this.serializer = serializer;
        }

        /**
         * Directory for the spill files (hardening L-1): spills hold the input data unencrypted,
         * and the default is the world-shared system temp directory. Point this at a locked-down
         * or ephemeral directory when the data is sensitive. The directory must exist; spill
         * files are still deleted after the merge (and registered delete-on-exit as a backstop).
         */
        public ExternalSortBuilder spillDir(java.nio.file.Path dir) {
            this.spillDir = dir;
            return this;
        }

        /**
         * Max number of elements per in-memory chunk (sorted run). Smaller values reduce heap
         * pressure per chunk at the cost of more runs and potentially more merge passes; larger
         * values sort fewer, larger runs. Default 100 000.
         */
        public ExternalSortBuilder runSize(int n) {
            if (n <= 0) throw new IllegalArgumentException("runSize must be positive: " + n);
            this.runSize = n;
            return this;
        }

        /**
         * Maximum number of sorted runs to merge in a single pass (the "fan-in" of the
         * tournament tree). When the run count exceeds this, the sorter does multiple passes until
         * ≤ {@code fanIn} runs remain. Default 16.
         */
        public ExternalSortBuilder fanIn(int k) {
            if (k < 2) throw new IllegalArgumentException("fanIn must be >= 2: " + k);
            this.fanIn = k;
            return this;
        }

        /**
         * Sort the source and return all results in an in-memory list. Suitable for testing and
         * cases where the final merged output fits in RAM; for truly out-of-core use
         * {@link #feedInto} to stream directly from the tournament tree into CSRBT.
         */
        public List<K> toList() throws IOException {
            return sorter().sortToList(source);
        }

        /**
         * Sort the source and stream-feed the merged output into {@code set} without
         * materialising the full output in memory — the true out-of-core path.
         */
        public ExternalSortResult feedInto(OrderedSet<K> set) throws IOException {
            return sorter().sortAndFeed(source, CsrbtTarget.of(set), 0);
        }

        /**
         * As {@link #feedInto(OrderedSet)} but bounded to {@code maxSize} — the same sliding-window
         * / top-N semantics as {@link BeefSort#streaming(OrderedSet, int)} (0 = unbounded).
         */
        public ExternalSortResult feedInto(OrderedSet<K> set, int maxSize) throws IOException {
            return sorter().sortAndFeed(source, CsrbtTarget.of(set), maxSize);
        }

        private ExternalMergeSorter<K> sorter() {
            return new ExternalMergeSorter<>(engine(), comparator, serializer, runSize, fanIn, spec(), spillDir);
        }
    }
}
