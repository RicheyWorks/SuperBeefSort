package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.superbeefsort.core.SortResult;
import io.github.richeyworks.superbeefsort.core.StrategyId;
import io.github.richeyworks.superbeefsort.feed.FeedMode;
import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.profile.Distribution;
import io.github.richeyworks.superbeefsort.profile.KeyStats;
import io.github.richeyworks.superbeefsort.profile.ProfileDepth;
import io.github.richeyworks.superbeefsort.registry.StrategyRegistry;
import io.github.richeyworks.superbeefsort.select.CostModelStrategySelector;
import io.github.richeyworks.superbeefsort.select.LearnedModelStrategySelector;
import io.github.richeyworks.superbeefsort.select.SelectionPolicy;
import io.github.richeyworks.superbeefsort.select.SelectorModel;
import io.github.richeyworks.superbeefsort.select.SortPlan;
import io.github.richeyworks.superbeefsort.select.StrategySelector;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4a learned selector (ADR action item 4): the model overrides the delegate only when SMART,
 * above the size + confidence gates, and the predicted strategy is applicable — otherwise it returns
 * the delegate's plan verbatim. Uses tiny inline models so the routing is deterministic, plus the
 * real exported model as a structural smoke test.
 */
class LearnedModelStrategySelectorTest {

    private final StrategyRegistry registry = StrategyRegistry.withDefaults();

    // A stub delegate whose pick (merge) differs from every model class, so an override is observable.
    private static final StrategySelector STUB_MERGE =
            (p, pol, reg) -> new SortPlan(StrategyId.of("merge"), FeedMode.BULK, StrategyId.of("intro"), "stub");

    // Routes on counting_feasible: <=0.5 -> intro, else -> counting (both fully confident).
    private static final String MODEL_FEASIBLE_SPLIT = String.join("\n",
            "sbs-selector-model 1 decision_tree",
            "features counting_feasible",
            "classes intro,counting",
            "n_nodes 3",
            "feature 0 -1 -1",
            "threshold 0.5 0 0",
            "left 1 -1 -1",
            "right 2 -1 -1",
            "class 0 0 1",
            "confidence 1.0 1.0 1.0");

    // Always predicts counting (single leaf), confidence configurable via the template.
    private static String alwaysCounting(double confidence) {
        return String.join("\n",
                "sbs-selector-model 1 decision_tree",
                "features size",
                "classes counting",
                "n_nodes 1",
                "feature -1",
                "threshold 0",
                "left -1",
                "right -1",
                "class 0",
                "confidence " + confidence);
    }

    // ---- profiles ---- //

    private static DataProfile intKeyed(int n, boolean countingFeasible) {
        return new DataProfile(n, 0.3, true, ProfileDepth.SHALLOW, n / 4,
                new KeyStats(0, 1000, countingFeasible), Distribution.CLUSTERED, 5, 2L * n, true);
    }

    private static DataProfile comparable(int n) {
        return new DataProfile(n, 0.3, false, ProfileDepth.SHALLOW, n,
                null, Distribution.UNKNOWN, 5, 2L * n, true);
    }

    private String pick(LearnedModelStrategySelector sel, DataProfile p, SelectionPolicy policy) {
        return sel.select(p, policy, registry).strategy().value();
    }

    private LearnedModelStrategySelector selector(String modelText) {
        return new LearnedModelStrategySelector(
                SelectorModel.parse(modelText), STUB_MERGE, 0.65, 256);
    }

    // ---- override behaviour ---- //

    @Test
    void overridesToCountingForFeasibleIntegerKeys() {
        var sel = selector(MODEL_FEASIBLE_SPLIT);
        assertEquals("counting", pick(sel, intKeyed(10_000, true), SelectionPolicy.SMART));
    }

    @Test
    void overridesToIntroForComparableOnly() {
        var sel = selector(MODEL_FEASIBLE_SPLIT);
        assertEquals("intro", pick(sel, comparable(10_000), SelectionPolicy.SMART));
    }

    // ---- the gates: when NOT to override ---- //

    @Test
    void deferToDelegateBelowSizeGate() {
        var sel = selector(MODEL_FEASIBLE_SPLIT);
        // n=100 < 256 size gate -> the stub delegate's pick (merge) stands.
        assertEquals("merge", pick(sel, intKeyed(100, true), SelectionPolicy.SMART));
    }

    @Test
    void deferToDelegateUnderNonSmartPolicy() {
        var sel = selector(MODEL_FEASIBLE_SPLIT);
        assertEquals("merge", pick(sel, intKeyed(10_000, true), SelectionPolicy.STABLE));
        assertEquals("merge", pick(sel, intKeyed(10_000, true), SelectionPolicy.FIXED_INTRO));
    }

    @Test
    void deferToDelegateBelowConfidenceMargin() {
        var sel = selector(alwaysCounting(0.50)); // 0.50 < 0.65 margin
        assertEquals("merge", pick(sel, intKeyed(10_000, true), SelectionPolicy.SMART));
    }

    @Test
    void deferWhenPredictedStrategyInapplicable() {
        // Model always says counting, but a comparable-only input can't run counting -> defer.
        var sel = selector(alwaysCounting(1.0));
        assertEquals("merge", pick(sel, comparable(10_000), SelectionPolicy.SMART));
        // Integer keys but counting infeasible (range too wide) -> also defer.
        assertEquals("merge", pick(sel, intKeyed(10_000, false), SelectionPolicy.SMART));
        // Integer keys + feasible -> counting applies, so it overrides.
        assertEquals("counting", pick(sel, intKeyed(10_000, true), SelectionPolicy.SMART));
    }

    // ---- observe forwarding ---- //

    @Test
    void observeForwardsToLearningDelegate() {
        int[] seen = {0};
        class LearningStub implements io.github.richeyworks.superbeefsort.select.LearningStrategySelector {
            @Override
            public SortPlan select(DataProfile p, SelectionPolicy pol, StrategyRegistry reg) {
                return new SortPlan(StrategyId.of("merge"), FeedMode.BULK, StrategyId.of("intro"), "stub");
            }
            @Override
            public void observe(DataProfile p, StrategyId s, SortResult o) {
                seen[0]++;
            }
        }
        var sel = new LearnedModelStrategySelector(
                SelectorModel.parse(MODEL_FEASIBLE_SPLIT), new LearningStub(), 0.65, 256);
        sel.observe(intKeyed(10_000, true), StrategyId.of("counting"),
                new SortResult(StrategyId.of("counting"), 10_000, 0L, 10_000L, 0L, 0L));
        assertEquals(1, seen[0]);
    }

    @Test
    void observeIsNoopForNonLearningDelegate() {
        var sel = selector(MODEL_FEASIBLE_SPLIT); // STUB_MERGE is not a LearningStrategySelector
        // must not throw
        sel.observe(intKeyed(10_000, true), StrategyId.of("counting"),
                new SortResult(StrategyId.of("counting"), 10_000, 0L, 10_000L, 0L, 0L));
    }

    // ---- SelectorModel parsing ---- //

    @Test
    void modelParsesShapeAndPredicts() {
        SelectorModel m = SelectorModel.parse(MODEL_FEASIBLE_SPLIT);
        assertEquals(1, m.featureColumns().size());
        assertEquals("counting_feasible", m.featureColumns().get(0));
        assertEquals(3, m.nodeCount());
        assertEquals("counting", m.predict(new double[]{1.0}).label());
        assertEquals("intro", m.predict(new double[]{0.0}).label());
        assertEquals(1.0, m.predict(new double[]{0.0}).confidence());
    }

    @Test
    void rejectsUnsupportedSchemaVersion() {
        String bad = MODEL_FEASIBLE_SPLIT.replace("sbs-selector-model 1", "sbs-selector-model 2");
        assertThrows(IllegalArgumentException.class, () -> SelectorModel.parse(bad));
    }

    @Test
    void rejectsRaggedNodeArrays() {
        String bad = MODEL_FEASIBLE_SPLIT.replace("class 0 0 1", "class 0 0"); // 2 != 3 nodes
        assertThrows(IllegalArgumentException.class, () -> SelectorModel.parse(bad));
    }

    // ---- the real exported model (structural smoke test) ---- //

    @Test
    void realExportedModelLoadsAndPredicts() {
        Optional<SelectorModel> loaded = SelectorModel.fromClasspath("/sbs_selector_model.txt");
        // Resource is shipped with the tests; if present, validate its shape and that it predicts.
        if (loaded.isEmpty()) {
            return; // resource absent in this build — nothing to assert
        }
        SelectorModel m = loaded.get();
        assertEquals(15, m.featureColumns().size());
        assertTrue(m.classes().contains("counting"));
        assertTrue(m.classes().contains("jdk.timsort"));

        // A real selector over the real model + cost-model delegate returns a runnable strategy.
        var sel = new LearnedModelStrategySelector(m, new CostModelStrategySelector(),
                LearnedModelStrategySelector.DEFAULT_CONFIDENCE_MARGIN,
                LearnedModelStrategySelector.DEFAULT_SIZE_GATE);
        SortPlan plan = sel.select(intKeyed(50_000, true), SelectionPolicy.SMART, registry);
        assertTrue(registry.contains(plan.strategy()), "selected strategy must be registered");
        assertFalse(plan.strategy().value().isEmpty());
    }
}
