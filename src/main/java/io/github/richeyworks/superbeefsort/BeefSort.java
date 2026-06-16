package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.control.MorphPolicy;
import io.github.richeyworks.csrbt.ensemble.EnsembleOrderedSet;
import io.github.richeyworks.csrbt.strategy.TreeStrategy;
import io.github.richeyworks.superbeefsort.core.ByteSequenceEncoder;
import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortObserver;
import io.github.richeyworks.superbeefsort.csrbt.AccessPolicy;
import io.github.richeyworks.superbeefsort.csrbt.EnsembleTargetFactory;
import io.github.richeyworks.superbeefsort.csrbt.ProfileGuidedScorer;
import io.github.richeyworks.superbeefsort.csrbt.StrategyAdvisor;
import io.github.richeyworks.superbeefsort.csrbt.WorkloadAdaptation;
import io.github.richeyworks.superbeefsort.engine.BeefSortEngine;
import io.github.richeyworks.superbeefsort.engine.JobSpec;
import io.github.richeyworks.superbeefsort.engine.SortRunResult;
import io.github.richeyworks.superbeefsort.feed.CsrbtTarget;
import io.github.richeyworks.superbeefsort.feed.FeedMode;
import io.github.richeyworks.superbeefsort.feed.FeedResult;
import io.github.richeyworks.superbeefsort.feed.HealthPolicy;
import io.github.richeyworks.superbeefsort.feed.ParallelFeeder;
import io.github.richeyworks.superbeefsort.feed.StreamingFeeder;
import io.github.richeyworks.superbeefsort.select.SelectionPolicy;
import io.github.richeyworks.superbeefsort.select.StrategySelector;
import io.github.richeyworks.superbeefsort.strategy.MsdRadixSortStrategy;
import io.github.richeyworks.superbeefsort.stream.AdaptiveStreamSorter;
import io.github.richeyworks.superbeefsort.stream.DriftDetector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
    private KeyEncoder<K> keyEncoder; // null -> comparison sorts only
    private StrategySelector selector; // null -> engine default (rule-based)
    private OptionalLong randomSeed = OptionalLong.empty(); // present -> deterministic, reproducible runs
    private AccessPolicy accessPolicy = AccessPolicy.BALANCED; // drives StrategyAdvisor for build*()
    private Supplier<? extends TreeStrategy<K>> targetStrategy; // null -> StrategyAdvisor decides
    private HealthPolicy healthPolicy = HealthPolicy.defaults(); // streaming batch size + self-heal cadence

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

    /** Override the strategy selector (e.g. a {@code CostModelStrategySelector}). */
    public BeefSort<K> selector(StrategySelector strategySelector) {
        this.selector = strategySelector;
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
        return OrderedSet.fromSorted(distinct, strategy, comparator);
    }

    /**
     * Sort, then <em>construct and bulk-load</em> a CSRBT {@link EnsembleOrderedSet} composed from the profile
     * (primary born with the access-advised strategy + a RedBlack replica), via the O(n)/member parallel
     * bulk path. The "ensemble as a first-class target" pattern — see docs/architecture-csrbt-integration.md §4.
     */
    public EnsembleOrderedSet<K> buildEnsemble() {
        SortRunResult<K> run = engine().sort(source, comparator, spec());
        EnsembleOrderedSet<K> ensemble = EnsembleTargetFactory.forProfile(run.profile(), accessPolicy, comparator);
        new ParallelFeeder<K>().feed(run.sorted(), CsrbtTarget.of(ensemble));
        return ensemble;
    }

    /**
     * Sort, construct the born-optimal {@link OrderedSet} (as {@link #buildOrderedSet()}), then wire it to
     * CSRBT's self-adaptive control plane under {@code policy}: the returned {@link WorkloadAdaptation} lets
     * you report live operations and have the tree re-tune its strategy to the observed workload — "born
     * optimal AND wired to adapt" (docs/architecture-csrbt-integration.md §5). The access pattern must map
     * to a morph-managed strategy (BALANCED/READ_HEAVY/SKEWED, or an RB/AVL/Splay/Hybrid targetStrategy).
     */
    public WorkloadAdaptation<K> buildAdaptive(MorphPolicy policy) {
        return WorkloadAdaptation.attach(buildOrderedSet(), policy);
    }

    /**
     * Sort, build a born-optimal {@link OrderedSet} in the morph-family strategy the data profile favors,
     * then wire it to CSRBT's control plane with a {@link ProfileGuidedScorer} prior toward that strategy —
     * "co-optimization": the sort's profile both shapes the tree at birth and primes its adaptation, which
     * then defers to the live workload (docs/architecture-csrbt-integration.md §5). Unlike
     * {@link #buildAdaptive(MorphPolicy)} this always uses a morph-managed strategy (so WRITE_HEAVY maps to
     * Red-Black here, not the static weight-balanced shape), guaranteeing the tree can adapt.
     */
    public WorkloadAdaptation<K> buildCoOptimized(MorphPolicy policy) {
        SortRunResult<K> run = engine().sort(source, comparator, spec());
        List<K> distinct = distinct(run.sorted());
        var favored = ProfileGuidedScorer.favoredStrategy(run.profile(), accessPolicy);
        TreeStrategy<K> born = (targetStrategy != null) ? targetStrategy.get() : favored.<K>newStrategy();
        OrderedSet<K> set = OrderedSet.fromSorted(distinct, born, comparator);
        return WorkloadAdaptation.attachProfileGuided(set, run.profile(), accessPolicy, policy);
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
                .selector(selector)
                .policy(policy)
                .detector(detector)
                .healthPolicy(healthPolicy)
                .observe(observer)
                .into(target, maxSize);
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
        return selector == null
                ? new BeefSortEngine<>(keyEncoder)
                : new BeefSortEngine<>(selector, keyEncoder);
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
        return s;
    }
}
