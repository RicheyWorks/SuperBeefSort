package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.ensemble.EnsembleOrderedSet;
import io.github.richeyworks.superbeefsort.core.KeyEncoder;
import io.github.richeyworks.superbeefsort.core.SortObserver;
import io.github.richeyworks.superbeefsort.engine.BeefSortEngine;
import io.github.richeyworks.superbeefsort.engine.JobSpec;
import io.github.richeyworks.superbeefsort.engine.SortRunResult;
import io.github.richeyworks.superbeefsort.feed.CsrbtTarget;
import io.github.richeyworks.superbeefsort.feed.FeedMode;
import io.github.richeyworks.superbeefsort.select.SelectionPolicy;
import io.github.richeyworks.superbeefsort.select.StrategySelector;

import java.util.Comparator;
import java.util.List;

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
 */
public final class BeefSort<K> {

    private final Comparator<? super K> comparator;
    private List<K> source;
    private SelectionPolicy policy = SelectionPolicy.SMART;
    private FeedMode feedMode; // null -> the plan decides
    private SortObserver observer = SortObserver.NOOP;
    private KeyEncoder<K> keyEncoder; // null -> comparison sorts only
    private StrategySelector selector; // null -> engine default (rule-based)

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

    private JobSpec spec() {
        JobSpec s = JobSpec.defaults().withPolicy(policy).withObserver(observer);
        return feedMode == null ? s : s.withFeedMode(feedMode);
    }
}
