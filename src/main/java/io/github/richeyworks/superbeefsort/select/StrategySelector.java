package io.github.richeyworks.superbeefsort.select;

import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.registry.StrategyRegistry;

/** Maps a {@link DataProfile} plus a {@link SelectionPolicy} to a {@link SortPlan}. */
public interface StrategySelector {

    SortPlan select(DataProfile profile, SelectionPolicy policy, StrategyRegistry registry);
}
