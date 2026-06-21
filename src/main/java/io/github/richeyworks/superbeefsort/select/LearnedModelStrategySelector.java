package io.github.richeyworks.superbeefsort.select;

import io.github.richeyworks.superbeefsort.core.SortResult;
import io.github.richeyworks.superbeefsort.core.StrategyCapabilities;
import io.github.richeyworks.superbeefsort.core.StrategyId;
import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.registry.StrategyRegistry;
import io.github.richeyworks.superbeefsort.strategy.CountingSortStrategy;

import java.util.Objects;

/**
 * A learned strategy selector (Phase 4a, ADR docs/adr-phase4-python-intelligence.md action item 4):
 * an in-process {@link SelectorModel} (a compact decision tree trained offline over the
 * {@link DataProfile} feature vector) that <em>advises</em> a {@link CostModelStrategySelector}
 * delegate. It overrides the delegate's pick only when (1) the policy is {@link SelectionPolicy#SMART}
 * — {@code STABLE}/{@code FIXED_INTRO} keep their guarantees; (2) the input is large enough that a
 * better pick amortizes the consult ({@link #sizeGate}); (3) the leaf's confidence clears the
 * {@link #confidenceMargin}; and (4) the predicted strategy is registered and <em>applicable</em> to
 * the profile. Otherwise it returns the delegate's plan verbatim.
 *
 * <p>This is the fallback-first discipline used everywhere in the engine: a missing, low-confidence,
 * or inapplicable model leaves selection byte-for-byte the cost-model path it is today. The model is
 * static (refreshed by retrain + redeploy, not online), so {@link #observe} just keeps the delegate
 * learning (and any upstream {@link ObservingStrategySelector} logging the corpus). On the gate
 * benchmark the underlying tree scores 98.1% exact-match / 0.50% mean regret vs the bandit's
 * 65.4% / 191.94% (ADR action item 3); this selector trades a little small-{@code n} optimality for
 * never paying inference on tiny jobs.</p>
 *
 * <p>Construct it with {@link SelectorModel#load} / {@link SelectorModel#fromClasspath}; e.g.
 * {@code new LearnedModelStrategySelector(SelectorModel.load(modelPath))} uses a fresh
 * {@code CostModelStrategySelector} delegate and the default gates. Pass it to
 * {@code BeefSort.selector(...)} like any other selector.</p>
 */
public final class LearnedModelStrategySelector implements LearningStrategySelector {

    /** Override only when the leaf's purity is at least this — below it, defer to the delegate. */
    public static final double DEFAULT_CONFIDENCE_MARGIN = 0.65;
    /** Override only at or above this size — tiny jobs aren't worth a different pick. */
    public static final int DEFAULT_SIZE_GATE = 256;

    private final SelectorModel model;
    private final StrategySelector delegate;
    private final double confidenceMargin;
    private final int sizeGate;

    public LearnedModelStrategySelector(SelectorModel model, StrategySelector delegate,
                                        double confidenceMargin, int sizeGate) {
        this.model = Objects.requireNonNull(model, "model");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (!(confidenceMargin >= 0.0 && confidenceMargin <= 1.0)) {
            throw new IllegalArgumentException("confidenceMargin must be in [0,1]: " + confidenceMargin);
        }
        if (sizeGate < 0) {
            throw new IllegalArgumentException("sizeGate must be >= 0: " + sizeGate);
        }
        this.confidenceMargin = confidenceMargin;
        this.sizeGate = sizeGate;
    }

    /** Wrap a fresh {@link CostModelStrategySelector} delegate with the default gates. */
    public LearnedModelStrategySelector(SelectorModel model) {
        this(model, new CostModelStrategySelector(), DEFAULT_CONFIDENCE_MARGIN, DEFAULT_SIZE_GATE);
    }

    @Override
    public SortPlan select(DataProfile profile, SelectionPolicy policy, StrategyRegistry registry) {
        SortPlan base = delegate.select(profile, policy, registry);

        // The model is a SMART-objective advisor; STABLE / FIXED_INTRO keep their own guarantees.
        if (policy != SelectionPolicy.SMART) {
            return base;
        }
        // Amortization gate: on tiny inputs the sort is microseconds — keep the delegate's pick.
        if (profile.size() < sizeGate) {
            return base;
        }
        SelectorModel.Prediction pred = model.predict(featureVector(profile));
        if (pred.confidence() < confidenceMargin) {
            return base; // not confident enough to override a reliable analytic pick
        }
        StrategyId id = StrategyId.of(pred.label());
        if (id.equals(base.strategy())) {
            return base; // model agrees with the delegate; nothing to change
        }
        if (!registry.contains(id) || !applicable(id, profile, registry)) {
            return base; // predicted strategy can't run on this input — defer
        }
        return new SortPlan(id, base.feedMode(), base.fallback(),
                String.format("learned model -> %s (p=%.2f)", pred.label(), pred.confidence()));
    }

    @Override
    public void observe(DataProfile profile, StrategyId strategy, SortResult outcome) {
        // The learned model is offline/static; just keep a learning delegate tuning itself.
        if (delegate instanceof LearningStrategySelector learner) {
            learner.observe(profile, strategy, outcome);
        }
    }

    /** The wrapped analytic selector consulted for the base plan and every deferral. */
    public StrategySelector delegate() {
        return delegate;
    }

    // ---- applicability + feature mapping ---- //

    /** Can {@code id} actually run on {@code profile}? Mirrors the capability gates selectors honour. */
    private static boolean applicable(StrategyId id, DataProfile profile, StrategyRegistry registry) {
        StrategyCapabilities caps = registry.get(id).capabilities();
        if (caps.requiresIntegerKeys() && !profile.hasKeyStats()) {
            return false;
        }
        if (caps.requiresByteSequenceEncoder() && !profile.hasByteSequenceKey()) {
            return false;
        }
        // CountingSort throws when the key range is too wide; gate on the profiler's feasibility flag.
        if (id.equals(CountingSortStrategy.ID)
                && (!profile.hasKeyStats() || !profile.keyStats().countingFeasible())) {
            return false;
        }
        return true;
    }

    /** Build the feature vector in the model's declared column order (full feature parity with training). */
    private double[] featureVector(DataProfile p) {
        var cols = model.featureColumns();
        double[] x = new double[cols.size()];
        for (int i = 0; i < x.length; i++) {
            x[i] = featureValue(cols.get(i), p);
        }
        return x;
    }

    /** One feature's value from the live profile — the Java twin of {@code tools/phase4/gen_corpus.features}. */
    private static double featureValue(String name, DataProfile p) {
        long maxInv = p.maxInversions();
        return switch (name) {
            case "size" -> p.size();
            case "sortedness_ratio" -> p.sortednessRatio();
            case "has_duplicates" -> p.hasDuplicatesSampled() ? 1.0 : 0.0;
            case "distinct_estimate" -> p.distinctEstimate();
            case "distinct_ratio" -> p.size() > 0 ? (double) p.distinctEstimate() / p.size() : 0.0;
            case "has_key_stats" -> p.hasKeyStats() ? 1.0 : 0.0;
            case "key_span" -> p.hasKeyStats() ? p.keyStats().span() : -1.0;
            case "counting_feasible" -> (p.hasKeyStats() && p.keyStats().countingFeasible()) ? 1.0 : 0.0;
            case "distribution_ord" -> p.distribution().ordinal();
            case "longest_run" -> p.longestRun();
            case "longest_run_ratio" -> p.size() > 0 ? (double) p.longestRun() / p.size() : 0.0;
            case "inversions" -> p.inversions();
            case "inversion_ratio" -> (p.inversions() >= 0 && maxInv > 0)
                    ? (double) p.inversions() / maxInv : 0.0;
            case "inversions_exact" -> p.inversionsExact() ? 1.0 : 0.0;
            case "has_byte_key" -> p.hasByteSequenceKey() ? 1.0 : 0.0;
            default -> throw new IllegalArgumentException("model references unknown feature: " + name);
        };
    }
}
