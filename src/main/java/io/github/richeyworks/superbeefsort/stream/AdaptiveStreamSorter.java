package io.github.richeyworks.superbeefsort.stream;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortEvent;
import io.github.richeyworks.superbeefsort.core.SortObserver;
import io.github.richeyworks.superbeefsort.core.SortResult;
import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.feed.CsrbtTarget;
import io.github.richeyworks.superbeefsort.feed.FeedResult;
import io.github.richeyworks.superbeefsort.feed.HealthPolicy;
import io.github.richeyworks.superbeefsort.feed.StreamingFeeder;
import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.profile.DataProfiler;
import io.github.richeyworks.superbeefsort.profile.IntelligentDataProfiler;
import io.github.richeyworks.superbeefsort.profile.ProfileDepth;
import io.github.richeyworks.superbeefsort.registry.StrategyRegistry;
import io.github.richeyworks.superbeefsort.select.LearningStrategySelector;
import io.github.richeyworks.superbeefsort.select.RuleBasedStrategySelector;
import io.github.richeyworks.superbeefsort.select.SelectionPolicy;
import io.github.richeyworks.superbeefsort.select.SortPlan;
import io.github.richeyworks.superbeefsort.select.StrategySelector;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * A drift-aware driver for long-running, multi-batch streams. Each {@link #accept(List) batch} is
 * profiled cheaply (SHALLOW), tested for concept drift against the regime the current strategy was
 * chosen for, sorted, and streamed into a bounded CSRBT target. The selector — the expensive step,
 * especially for a cost-model or bandit selector — runs <em>only</em> on the first batch and whenever
 * the {@link DriftDetector} declares the distribution has moved; otherwise the cached {@link SortPlan}
 * is reused. The result is "stable until the data changes": the engine does not thrash its strategy on
 * batch-to-batch noise, but it does re-tune when the workload genuinely shifts.
 *
 * <p>This is the sort-side mirror of CSRBT's self-adaptive {@code MorphController}
 * ({@link io.github.richeyworks.superbeefsort.csrbt.WorkloadAdaptation}): that re-tunes the <em>tree</em>
 * strategy to the live <em>access</em> pattern; this re-tunes the <em>sort</em> strategy to the live
 * <em>data</em> distribution. Both gate change behind an anti-thrash policy (threshold + warmup +
 * cooldown here; margin + stability + cooldown there). See docs/architecture-csrbt-integration.md §2.3.</p>
 *
 * <p>Feeding reuses the {@link StreamingFeeder}: the target is capped to {@code maxSize} and, because
 * each batch is fed ascending and CSRBT FIFO-evicts the oldest-inserted key, a bounded stream converges
 * to a sliding window of the largest distinct keys. {@code maxSize <= 0} streams unbounded. Construct
 * via {@link #builder(Comparator)} or {@code BeefSort.adaptiveStream(...)}. Not thread-safe — one driver
 * drives one stream.</p>
 */
public final class AdaptiveStreamSorter<K> {

    private final Comparator<? super K> comparator;
    private final KeyEncoder<K> keyEncoder; // may be null
    private final StrategyRegistry registry;
    private final DataProfiler<K> profiler;
    private final StrategySelector selector;
    private final SelectionPolicy policy;
    private final DriftDetector detector;
    private final CsrbtTarget<K> target;
    private final int maxSize;
    private final HealthPolicy healthPolicy;
    private final SortObserver observer;

    private SortPlan currentPlan; // cached selection, refreshed only on drift
    private int batchIndex;
    private int reselections;

    private AdaptiveStreamSorter(Builder<K> b, CsrbtTarget<K> target, int maxSize) {
        this.comparator = Objects.requireNonNull(b.comparator, "comparator");
        this.keyEncoder = b.keyEncoder;
        this.registry = b.registry != null ? b.registry : StrategyRegistry.withDefaults();
        this.profiler = b.profiler != null ? b.profiler : new IntelligentDataProfiler<>();
        this.selector = b.selector != null ? b.selector : new RuleBasedStrategySelector();
        this.policy = b.policy != null ? b.policy : SelectionPolicy.SMART;
        this.detector = b.detector != null ? b.detector : new DriftDetector();
        this.healthPolicy = b.healthPolicy != null ? b.healthPolicy : HealthPolicy.defaults();
        this.observer = b.observer != null ? b.observer : SortObserver.NOOP;
        this.target = Objects.requireNonNull(target, "target");
        this.maxSize = maxSize;
    }

    public static <K> Builder<K> builder(Comparator<? super K> comparator) {
        return new Builder<>(comparator);
    }

    /**
     * Profile, drift-test, sort, and stream-feed one batch. Re-selects the strategy iff the detector
     * declares drift (or this is the first batch). Returns the per-batch {@link StreamSortResult}.
     */
    public StreamSortResult<K> accept(List<K> batch) {
        int idx = ++batchIndex;
        SortBuffer<K> buffer = SortBuffer.of(batch, comparator, keyEncoder);

        DataProfile profile = profiler.profile(buffer, ProfileDepth.SHALLOW);
        DriftVerdict verdict = detector.test(DriftSignal.from(profile));

        boolean reselected = false;
        if (verdict.drift() || currentPlan == null) {
            currentPlan = selector.select(profile, policy, registry);
            reselected = true;
            reselections++;
            observer.onEvent(SortEvent.of(SortEvent.Type.PLAN_SELECTED,
                    "batch " + idx + " -> " + currentPlan.strategy().value()
                            + " (" + verdict.reason() + "; " + currentPlan.rationale() + ")"));
        }

        SortStrategy<K> strategy = resolve(currentPlan, buffer);
        long beforeComparisons = buffer.comparisons();
        long beforeMoves = buffer.moves();
        long t0 = System.nanoTime();
        strategy.sort(buffer, new SortContext(observer, OptionalLong.empty()));
        long elapsed = System.nanoTime() - t0;
        SortResult metrics = new SortResult(strategy.id(), buffer.size(),
                buffer.comparisons() - beforeComparisons, buffer.moves() - beforeMoves, elapsed);

        // Close the learning loop for self-tuning selectors, exactly as BeefSortEngine.sort does.
        if (selector instanceof LearningStrategySelector learner) {
            learner.observe(profile, metrics.strategyId(), metrics);
        }

        FeedResult feed = new StreamingFeeder<K>(maxSize, healthPolicy).feed(buffer.toList(), target);
        return new StreamSortResult<>(idx, profile, currentPlan, reselected, verdict.score(),
                verdict.reason(), metrics, feed);
    }

    /** Number of batches processed so far. */
    public int batchesProcessed() {
        return batchIndex;
    }

    /** Number of strategy (re)selections so far — the first selection plus one per drift fired. */
    public int reselections() {
        return reselections;
    }

    /** The plan currently in force (null before the first batch). */
    public SortPlan currentPlan() {
        return currentPlan;
    }

    /** The drift detector driving re-selection. */
    public DriftDetector detector() {
        return detector;
    }

    /** The bounded CSRBT target this stream feeds into. */
    public CsrbtTarget<K> target() {
        return target;
    }

    // Mirrors BeefSortEngine.resolve: honor the plan, but fall back if the pick needs integer keys we lack.
    private SortStrategy<K> resolve(SortPlan plan, SortBuffer<K> buffer) {
        SortStrategy<K> chosen = registry.contains(plan.strategy())
                ? registry.get(plan.strategy())
                : registry.get(plan.fallback());
        if (chosen.capabilities().requiresIntegerKeys() && !buffer.hasKeyEncoder()) {
            chosen = registry.get(plan.fallback());
        }
        return chosen;
    }

    /** Fluent builder; terminal {@code into(...)} binds the target + window and returns the driver. */
    public static final class Builder<K> {
        private final Comparator<? super K> comparator;
        private KeyEncoder<K> keyEncoder;
        private StrategyRegistry registry;
        private DataProfiler<K> profiler;
        private StrategySelector selector;
        private SelectionPolicy policy = SelectionPolicy.SMART;
        private DriftDetector detector;
        private HealthPolicy healthPolicy = HealthPolicy.defaults();
        private SortObserver observer = SortObserver.NOOP;

        private Builder(Comparator<? super K> comparator) {
            this.comparator = comparator;
        }

        /** Supply an order-faithful integer encoding to unlock non-comparison sorts (counting / radix). */
        public Builder<K> keyEncoder(KeyEncoder<K> encoder) {
            this.keyEncoder = encoder;
            return this;
        }

        /** Override the strategy selector (e.g. a cost-model or bandit selector reused across the stream). */
        public Builder<K> selector(StrategySelector strategySelector) {
            this.selector = strategySelector;
            return this;
        }

        public Builder<K> policy(SelectionPolicy p) {
            this.policy = p;
            return this;
        }

        /** Override the drift detector (threshold / warmup / cooldown). */
        public Builder<K> detector(DriftDetector driftDetector) {
            this.detector = driftDetector;
            return this;
        }

        /** Health/backpressure policy for the per-batch streaming feed. */
        public Builder<K> healthPolicy(HealthPolicy hp) {
            this.healthPolicy = hp;
            return this;
        }

        public Builder<K> observe(SortObserver o) {
            this.observer = o;
            return this;
        }

        public Builder<K> registry(StrategyRegistry r) {
            this.registry = r;
            return this;
        }

        public Builder<K> profiler(DataProfiler<K> p) {
            this.profiler = p;
            return this;
        }

        /** Bind a bounded {@link OrderedSet} target (capacity {@code maxSize}; {@code <= 0} for unbounded). */
        public AdaptiveStreamSorter<K> into(OrderedSet<K> target, int maxSize) {
            return new AdaptiveStreamSorter<>(this, CsrbtTarget.of(target), maxSize);
        }

        /** Bind an arbitrary {@link CsrbtTarget} (capacity {@code maxSize}; {@code <= 0} for unbounded). */
        public AdaptiveStreamSorter<K> into(CsrbtTarget<K> target, int maxSize) {
            return new AdaptiveStreamSorter<>(this, target, maxSize);
        }
    }
}
