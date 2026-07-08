package io.github.richeyworks.superbeefsort.select;

import io.github.richeyworks.superbeefsort.core.SortResult;
import io.github.richeyworks.superbeefsort.core.StrategyCapabilities;
import io.github.richeyworks.superbeefsort.core.StrategyId;
import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.registry.StrategyRegistry;
import io.github.richeyworks.superbeefsort.strategy.CountingSortStrategy;

import java.util.Objects;
import java.util.Optional;

/**
 * Phase 4b remote learned selector (ADR docs/adr-phase4-python-intelligence.md action item 6): consults an
 * {@link IntelligenceClient} (the gRPC SbsIntelligence service) to <em>advise</em> a local delegate, with the
 * exact fallback-first gating of the in-process {@link LearnedModelStrategySelector}. It overrides the
 * delegate's pick only when (1) policy is {@link SelectionPolicy#SMART}; (2) the input is large enough to
 * amortize the consult ({@link #sizeGate}); (3) the service returns advice with confidence at least
 * {@link #confidenceMargin}; and (4) the advised strategy is registered and <em>applicable</em> to the profile.
 *
 * <p>For <b>any</b> other case — non-SMART, below the gate, no advice, low confidence, an inapplicable pick, or
 * the service being unavailable (the client returns {@link Optional#empty()}) — it returns the delegate's plan
 * verbatim. The engine therefore never depends on the service.</p>
 *
 * <p>The per-call deadline and circuit breaker live <em>inside</em> the {@link IntelligenceClient}
 * implementation (the optional {@code sbs-intelligence-client} module), so this selector stays grpc-free and is
 * trivially testable with a fake client. {@link #observe} reports the outcome to the service best-effort (for
 * continual learning) and keeps any learning delegate tuning. Pass it to {@code BeefSort.selector(...)} like
 * any other selector; with {@link IntelligenceClient#disabled()} it is exactly its delegate.</p>
 */
public final class RemoteStrategySelector implements LearningStrategySelector {

    /** Override only when advice confidence is at least this — below it, defer to the delegate. */
    public static final double DEFAULT_CONFIDENCE_MARGIN = LearnedModelStrategySelector.DEFAULT_CONFIDENCE_MARGIN;
    /** Override only at or above this size — tiny jobs aren't worth a network consult. */
    public static final int DEFAULT_SIZE_GATE = LearnedModelStrategySelector.DEFAULT_SIZE_GATE;

    private final IntelligenceClient client;
    private final StrategySelector delegate;
    private final double confidenceMargin;
    private final int sizeGate;

    public RemoteStrategySelector(IntelligenceClient client, StrategySelector delegate,
                                  double confidenceMargin, int sizeGate) {
        this.client = Objects.requireNonNull(client, "client");
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
    public RemoteStrategySelector(IntelligenceClient client) {
        this(client, new CostModelStrategySelector(), DEFAULT_CONFIDENCE_MARGIN, DEFAULT_SIZE_GATE);
    }

    @Override
    public SortPlan select(DataProfile profile, SelectionPolicy policy, StrategyRegistry registry) {
        SortPlan base = delegate.select(profile, policy, registry);

        // The service advises the SMART objective only; STABLE / FIXED_INTRO keep their own guarantees.
        if (policy != SelectionPolicy.SMART) {
            return base;
        }
        // Amortization gate: tiny inputs sort in microseconds — never worth a network round trip.
        if (profile.size() < sizeGate) {
            return base;
        }
        Optional<IntelligenceClient.Advice> advice = client.predict(profile, policy);
        if (advice.isEmpty()) {
            return base; // no advice, or the service is unavailable -> local delegate (fallback-first)
        }
        IntelligenceClient.Advice a = advice.get();
        if (a.confidence() < confidenceMargin) {
            return base;
        }
        StrategyId id = StrategyId.of(a.strategyId());
        if (id.equals(base.strategy())) {
            return base; // service agrees with the delegate; nothing to change
        }
        if (!registry.contains(id) || !applicable(id, profile, registry)) {
            return base; // advised strategy can't run on this input -> defer
        }
        return new SortPlan(id, base.feedMode(), base.fallback(),
                String.format("remote model %s -> %s (p=%.2f)", a.modelVersion(), a.strategyId(), a.confidence()));
    }

    @Override
    public void observe(DataProfile profile, StrategyId strategy, SortResult outcome) {
        client.observe(profile, strategy, outcome); // best-effort; the contract says it never throws
        if (delegate instanceof LearningStrategySelector learner) {
            learner.observe(profile, strategy, outcome);
        }
    }

    /** The wrapped local selector consulted for the base plan and every deferral. */
    public StrategySelector delegate() {
        return delegate;
    }

    /** Can {@code id} actually run on {@code profile}? Mirrors {@link LearnedModelStrategySelector}'s gate. */
    private static boolean applicable(StrategyId id, DataProfile profile, StrategyRegistry registry) {
        StrategyCapabilities caps = registry.get(id).capabilities();
        if (caps.requiresIntegerKeys() && !profile.hasKeyStats()) {
            return false;
        }
        if (caps.requiresByteSequenceEncoder() && !profile.hasByteSequenceKey()) {
            return false;
        }
        if (id.equals(CountingSortStrategy.ID)
                && (!profile.hasKeyStats() || !profile.keyStats().countingFeasible())) {
            return false;
        }
        return true;
    }
}
