package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.superbeefsort.core.SortResult;
import io.github.richeyworks.superbeefsort.core.StrategyId;
import io.github.richeyworks.superbeefsort.feed.FeedMode;
import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.profile.Distribution;
import io.github.richeyworks.superbeefsort.profile.KeyStats;
import io.github.richeyworks.superbeefsort.profile.ProfileDepth;
import io.github.richeyworks.superbeefsort.registry.StrategyRegistry;
import io.github.richeyworks.superbeefsort.select.IntelligenceClient;
import io.github.richeyworks.superbeefsort.select.LearningStrategySelector;
import io.github.richeyworks.superbeefsort.select.RemoteStrategySelector;
import io.github.richeyworks.superbeefsort.select.SelectionPolicy;
import io.github.richeyworks.superbeefsort.select.SortPlan;
import io.github.richeyworks.superbeefsort.select.StrategySelector;
import io.github.richeyworks.superbeefsort.strategy.IntroSortStrategy;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** RemoteStrategySelector gating + fallback, driven by a fake IntelligenceClient (no grpc). */
class RemoteStrategySelectorTest {

    private final StrategyRegistry registry = StrategyRegistry.withDefaults();

    /** A delegate that always picks intro, so any other pick is the remote override. */
    private final StrategySelector introDelegate =
            (p, pol, reg) -> new SortPlan(IntroSortStrategy.ID, FeedMode.BULK, IntroSortStrategy.ID, "stub");

    private static DataProfile keyed(int n, KeyStats ks) {
        return new DataProfile(n, 0.5, false, ProfileDepth.SHALLOW, n, ks, Distribution.UNKNOWN);
    }

    private static DataProfile comparable(int n) {
        return new DataProfile(n, 0.5, false, ProfileDepth.SHALLOW, n, null, Distribution.UNKNOWN);
    }

    /** Returns a fixed advice (or empty) and counts observe calls. */
    static final class FakeClient implements IntelligenceClient {
        private final Optional<Advice> advice;
        int observeCount = 0;

        FakeClient(Optional<Advice> advice) {
            this.advice = advice;
        }

        @Override
        public Optional<Advice> predict(DataProfile profile, SelectionPolicy policy) {
            return advice;
        }

        @Override
        public void observe(DataProfile profile, StrategyId chosen, SortResult outcome) {
            observeCount++;
        }
    }

    private String pick(IntelligenceClient client, DataProfile p, SelectionPolicy policy) {
        return new RemoteStrategySelector(client, introDelegate,
                RemoteStrategySelector.DEFAULT_CONFIDENCE_MARGIN, RemoteStrategySelector.DEFAULT_SIZE_GATE)
                .select(p, policy, registry).strategy().value();
    }

    @Test
    void confidentApplicableAdviceOverridesDelegate() {
        IntelligenceClient c = new FakeClient(Optional.of(new IntelligenceClient.Advice("counting", 0.95, "m1")));
        assertEquals("counting", pick(c, keyed(50_000, new KeyStats(0, 50_000, true)), SelectionPolicy.SMART));
    }

    @Test
    void noAdviceFallsBackToDelegate() {
        // service down / no advice -> the engine never depends on it
        assertEquals("intro", pick(new FakeClient(Optional.empty()),
                keyed(50_000, new KeyStats(0, 50_000, true)), SelectionPolicy.SMART));
    }

    @Test
    void lowConfidenceFallsBack() {
        IntelligenceClient c = new FakeClient(Optional.of(new IntelligenceClient.Advice("counting", 0.50, "m1")));
        assertEquals("intro", pick(c, keyed(50_000, new KeyStats(0, 50_000, true)), SelectionPolicy.SMART));
    }

    @Test
    void belowSizeGateFallsBack() {
        IntelligenceClient c = new FakeClient(Optional.of(new IntelligenceClient.Advice("counting", 0.99, "m1")));
        assertEquals("intro", pick(c, keyed(100, new KeyStats(0, 50_000, true)), SelectionPolicy.SMART));
    }

    @Test
    void nonSmartPolicyFallsBack() {
        IntelligenceClient c = new FakeClient(Optional.of(new IntelligenceClient.Advice("counting", 0.99, "m1")));
        assertEquals("intro", pick(c, keyed(50_000, new KeyStats(0, 50_000, true)), SelectionPolicy.STABLE));
    }

    @Test
    void inapplicableAdviceFallsBack() {
        // advise counting but the profile has no integer key stats -> counting can't run -> defer
        IntelligenceClient c = new FakeClient(Optional.of(new IntelligenceClient.Advice("counting", 0.99, "m1")));
        assertEquals("intro", pick(c, comparable(50_000), SelectionPolicy.SMART));
    }

    @Test
    void unregisteredAdviceFallsBack() {
        IntelligenceClient c = new FakeClient(Optional.of(new IntelligenceClient.Advice("totally.bogus", 0.99, "m1")));
        assertEquals("intro", pick(c, keyed(50_000, new KeyStats(0, 50_000, true)), SelectionPolicy.SMART));
    }

    @Test
    void disabledClientIsExactlyTheDelegate() {
        assertEquals("intro", pick(IntelligenceClient.disabled(),
                keyed(50_000, new KeyStats(0, 50_000, true)), SelectionPolicy.SMART));
    }

    @Test
    void observeReportsToClientAndForwardsToLearningDelegate() {
        FakeClient c = new FakeClient(Optional.empty());
        LearningDelegate learning = new LearningDelegate();
        RemoteStrategySelector sel = new RemoteStrategySelector(c, learning,
                RemoteStrategySelector.DEFAULT_CONFIDENCE_MARGIN, RemoteStrategySelector.DEFAULT_SIZE_GATE);
        sel.observe(comparable(1000), IntroSortStrategy.ID, null); // fakes don't read the SortResult
        assertEquals(1, c.observeCount);
        assertEquals(1, learning.observeCount);
    }

    @Test
    void rejectsBadGates() {
        assertThrows(IllegalArgumentException.class,
                () -> new RemoteStrategySelector(IntelligenceClient.disabled(), introDelegate, 1.5, 256));
        assertThrows(IllegalArgumentException.class,
                () -> new RemoteStrategySelector(IntelligenceClient.disabled(), introDelegate, 0.65, -1));
    }

    /** A learning delegate that records observe forwarding. */
    private static final class LearningDelegate implements LearningStrategySelector {
        int observeCount = 0;

        @Override
        public SortPlan select(DataProfile profile, SelectionPolicy policy, StrategyRegistry registry) {
            return new SortPlan(IntroSortStrategy.ID, FeedMode.BULK, IntroSortStrategy.ID, "stub");
        }

        @Override
        public void observe(DataProfile profile, StrategyId chosen, SortResult outcome) {
            observeCount++;
        }
    }
}
