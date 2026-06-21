package io.github.richeyworks.superbeefsort.select;

import io.github.richeyworks.superbeefsort.core.SortResult;
import io.github.richeyworks.superbeefsort.core.StrategyId;
import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.profile.KeyStats;
import io.github.richeyworks.superbeefsort.registry.StrategyRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * A {@link LearningStrategySelector} decorator that logs every observed outcome to an {@link ObservationSink}
 * — the Phase 4 training-corpus tap (docs/adr-phase4-python-intelligence.md, action item 1). It delegates
 * {@link #select} unchanged and, on {@link #observe}, writes the {@code {profile features, strategy, measured
 * cost}} row before forwarding to the wrapped selector when that selector also learns.
 *
 * <p>Purely additive: selection behaviour is exactly the delegate's, so this can wrap the cost-model, bandit,
 * or rule-based selector to harvest a labeled dataset without changing which strategy is chosen. Shipping this
 * first (per the ADR) yields the corpus an offline model trains on, and on its own measures how often the
 * existing heuristics are sub-optimal — i.e. whether a learned selector is worth building at all.</p>
 *
 * <p>{@link #csvHeader()} / {@link #csvRow} define the stable, versionable column schema the offline trainer
 * reads; {@link #csvSink(Appendable)} is a ready thread-safe sink that writes that CSV (the header lazily,
 * just before the first row).</p>
 */
public final class ObservingStrategySelector implements LearningStrategySelector {

    private final StrategySelector delegate;
    private final ObservationSink sink;

    public ObservingStrategySelector(StrategySelector delegate, ObservationSink sink) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    @Override
    public SortPlan select(DataProfile profile, SelectionPolicy policy, StrategyRegistry registry) {
        return delegate.select(profile, policy, registry);
    }

    @Override
    public void observe(DataProfile profile, StrategyId strategy, SortResult outcome) {
        sink.record(profile, strategy, outcome);
        if (delegate instanceof LearningStrategySelector learner) {
            learner.observe(profile, strategy, outcome); // keep the wrapped selector learning
        }
    }

    /** The wrapped selector whose selection is used verbatim. */
    public StrategySelector delegate() {
        return delegate;
    }

    // ---- CSV training-corpus schema (the SBS -> offline-trainer contract) ----

    private static final String[] COLUMNS = {
            // DataProfile features
            "size", "sortednessRatio", "hasDuplicates", "depth", "distinctEstimate",
            "keyMin", "keyMax", "keySpan", "countingFeasible",
            "distribution", "longestRun", "inversions", "inversionsExact", "hasByteKey",
            // label + measured outcome
            "strategy", "comparisons", "moves", "elapsedNanos", "peakAuxBytes"
    };

    /** The stable CSV header; the column order is the schema contract — change it deliberately and version it. */
    public static String csvHeader() {
        return String.join(",", COLUMNS);
    }

    /** One CSV row: the profile features, the chosen strategy (label), and the measured outcome. */
    public static String csvRow(DataProfile p, StrategyId strategy, SortResult o) {
        KeyStats ks = p.keyStats();
        return new StringBuilder(192)
                .append(p.size()).append(',')
                .append(p.sortednessRatio()).append(',')
                .append(p.hasDuplicatesSampled()).append(',')
                .append(p.depth()).append(',')
                .append(p.distinctEstimate()).append(',')
                .append(ks != null ? Long.toString(ks.min()) : "").append(',')
                .append(ks != null ? Long.toString(ks.max()) : "").append(',')
                .append(ks != null ? Long.toString(ks.span()) : "").append(',')
                .append(ks != null ? Boolean.toString(ks.countingFeasible()) : "").append(',')
                .append(p.distribution()).append(',')
                .append(p.longestRun()).append(',')
                .append(p.inversions()).append(',')
                .append(p.inversionsExact()).append(',')
                .append(p.hasByteSequenceKey()).append(',')
                .append(strategy.value()).append(',')
                .append(o.comparisons()).append(',')
                .append(o.moves()).append(',')
                .append(o.elapsedNanos()).append(',')
                .append(o.peakAuxBytes())
                .toString();
    }

    /**
     * A thread-safe {@link ObservationSink} that appends {@link #csvRow CSV rows} to {@code out}, writing the
     * {@link #csvHeader() header} lazily just before the first row. {@link IOException}s are rethrown as
     * {@link UncheckedIOException} (the {@code observe} contract returns {@code void}); the caller owns
     * {@code out}'s lifecycle (flush/close).
     */
    public static ObservationSink csvSink(Appendable out) {
        Objects.requireNonNull(out, "out");
        return new ObservationSink() {
            private boolean headerWritten = false;

            @Override
            public synchronized void record(DataProfile profile, StrategyId strategy, SortResult outcome) {
                try {
                    if (!headerWritten) {
                        out.append(csvHeader()).append('\n');
                        headerWritten = true;
                    }
                    out.append(csvRow(profile, strategy, outcome)).append('\n');
                } catch (IOException e) {
                    throw new UncheckedIOException("failed to write observation row", e);
                }
            }
        };
    }
}
