package io.github.richeyworks.superbeefsort.engine;

import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortEvent;
import io.github.richeyworks.superbeefsort.core.SortObserver;
import io.github.richeyworks.superbeefsort.core.SortResult;
import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.feed.BalancedBuildFeeder;
import io.github.richeyworks.superbeefsort.feed.BulkBuildFeeder;
import io.github.richeyworks.superbeefsort.feed.CsrbtTarget;
import io.github.richeyworks.superbeefsort.feed.DirectFeeder;
import io.github.richeyworks.superbeefsort.feed.FeedMode;
import io.github.richeyworks.superbeefsort.feed.FeedResult;
import io.github.richeyworks.superbeefsort.feed.HealthGatedFeeder;
import io.github.richeyworks.superbeefsort.feed.SortFeeder;
import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.profile.DataProfiler;
import io.github.richeyworks.superbeefsort.profile.IntelligentDataProfiler;
import io.github.richeyworks.superbeefsort.profile.ProfileDepth;
import io.github.richeyworks.superbeefsort.registry.StrategyRegistry;
import io.github.richeyworks.superbeefsort.select.LearningStrategySelector;
import io.github.richeyworks.superbeefsort.select.RuleBasedStrategySelector;
import io.github.richeyworks.superbeefsort.select.SortPlan;
import io.github.richeyworks.superbeefsort.select.StrategySelector;

import java.util.Comparator;
import java.util.List;

/**
 * Orchestrates the pipeline: profile -> select -> sort -> (feed). Pure Java; the profiler, selector,
 * registry and feeders are all pluggable. An optional {@link KeyEncoder} threads through to the
 * buffer so the profiler and non-comparison strategies can use it; if a chosen strategy needs integer
 * keys but none are available, the engine falls back to the plan's comparison-based fallback.
 */
public final class BeefSortEngine<K> {

    private final StrategyRegistry registry;
    private final DataProfiler<K> profiler;
    private final StrategySelector selector;
    private final KeyEncoder<K> keyEncoder; // may be null

    public BeefSortEngine() {
        this((KeyEncoder<K>) null);
    }

    public BeefSortEngine(KeyEncoder<K> keyEncoder) {
        this(StrategyRegistry.withDefaults(), new IntelligentDataProfiler<>(), new RuleBasedStrategySelector(),
                keyEncoder);
    }

    /** Custom selector with the default registry + profiler. */
    public BeefSortEngine(StrategySelector selector, KeyEncoder<K> keyEncoder) {
        this(StrategyRegistry.withDefaults(), new IntelligentDataProfiler<>(), selector, keyEncoder);
    }

    public BeefSortEngine(StrategyRegistry registry, DataProfiler<K> profiler, StrategySelector selector) {
        this(registry, profiler, selector, null);
    }

    public BeefSortEngine(StrategyRegistry registry, DataProfiler<K> profiler, StrategySelector selector,
                          KeyEncoder<K> keyEncoder) {
        this.registry = registry;
        this.profiler = profiler;
        this.selector = selector;
        this.keyEncoder = keyEncoder;
    }

    /** Sort only — returns the sorted list and metrics. */
    public SortRunResult<K> sort(List<K> data, Comparator<? super K> comparator, JobSpec spec) {
        SortObserver obs = spec.observer();
        obs.onEvent(SortEvent.of(SortEvent.Type.JOB_STARTED, "n=" + data.size()));

        SortBuffer<K> buffer = SortBuffer.of(data, comparator, keyEncoder);

        DataProfile profile = profiler.profile(buffer, ProfileDepth.SHALLOW);
        obs.onEvent(SortEvent.of(SortEvent.Type.PROFILED,
                "sortedness=" + Math.round(profile.sortednessRatio() * 100) + "%, ~"
                        + profile.distinctEstimate() + " distinct"));

        SortPlan plan = selector.select(profile, spec.policy(), registry);
        SortStrategy<K> strategy = resolve(plan, buffer);
        obs.onEvent(SortEvent.of(SortEvent.Type.PLAN_SELECTED, strategy.id() + " (" + plan.rationale() + ")"));

        // Snapshot counters after profiling so metrics reflect the sort itself, not the profile pass.
        long beforeComparisons = buffer.comparisons();
        long beforeMoves = buffer.moves();
        long t0 = System.nanoTime();
        strategy.sort(buffer, new SortContext(obs));
        long elapsed = System.nanoTime() - t0;

        SortResult metrics = new SortResult(strategy.id(), buffer.size(),
                buffer.comparisons() - beforeComparisons, buffer.moves() - beforeMoves, elapsed);

        // Close the learning loop: a self-tuning selector observes what the chosen strategy actually
        // cost on this input, so its next choice for similar data is informed by reality. Opt-in —
        // stateless selectors don't implement this and pay nothing.
        if (selector instanceof LearningStrategySelector learner) {
            learner.observe(profile, metrics.strategyId(), metrics);
        }

        obs.onEvent(SortEvent.of(SortEvent.Type.SORT_COMPLETED,
                strategy.id() + " in " + String.format("%.2f", metrics.elapsedMillis()) + " ms"));

        List<K> sorted = buffer.toList();
        obs.onEvent(SortEvent.of(SortEvent.Type.JOB_COMPLETED, "sort-only"));
        return new SortRunResult<>(profile, plan, sorted, metrics, null);
    }

    /** Sort {@code data} then feed it into {@code target}, using the comparator the target orders by. */
    public SortRunResult<K> sortAndFeed(List<K> data, CsrbtTarget<K> target, JobSpec spec) {
        Comparator<? super K> comparator = requireComparator(target);
        SortObserver obs = spec.observer();

        SortRunResult<K> sortRun = sort(data, comparator, spec);

        FeedMode mode = spec.feedModeOverride() != null ? spec.feedModeOverride() : sortRun.plan().feedMode();
        SortFeeder<K> feeder = feederFor(mode);

        obs.onEvent(SortEvent.of(SortEvent.Type.FEED_STARTED, mode.toString()));
        FeedResult feedResult = feeder.feed(sortRun.sorted(), target);
        obs.onEvent(SortEvent.of(SortEvent.Type.FEED_COMPLETED,
                feedResult.inserted() + " inserted, healthy=" + feedResult.healthy()));

        return new SortRunResult<>(sortRun.profile(), sortRun.plan(), sortRun.sorted(),
                sortRun.sortMetrics(), feedResult);
    }

    private SortStrategy<K> resolve(SortPlan plan, SortBuffer<K> buffer) {
        SortStrategy<K> chosen = registry.contains(plan.strategy())
                ? registry.get(plan.strategy())
                : registry.get(plan.fallback());
        if (chosen.capabilities().requiresIntegerKeys() && !buffer.hasKeyEncoder()) {
            chosen = registry.get(plan.fallback());
        }
        return chosen;
    }

    private SortFeeder<K> feederFor(FeedMode mode) {
        return switch (mode) {
            case DIRECT -> new DirectFeeder<>();
            case BALANCED -> new BalancedBuildFeeder<>();
            case BULK -> new BulkBuildFeeder<>();
            case HEALTH_GATED -> new HealthGatedFeeder<>();
        };
    }

    private Comparator<? super K> requireComparator(CsrbtTarget<K> target) {
        Comparator<? super K> c = target.comparator();
        if (c == null) {
            throw new IllegalStateException(
                    "CSRBT target exposes no comparator; construct CsrbtTarget.of(collection, comparator) "
                    + "explicitly so SuperBeefSort sorts under the exact order CSRBT inserts under.");
        }
        return c;
    }
}
