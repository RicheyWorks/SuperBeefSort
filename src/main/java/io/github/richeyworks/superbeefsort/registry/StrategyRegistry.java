package io.github.richeyworks.superbeefsort.registry;

import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.core.StrategyId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Holds the available {@link SortStrategy} instances, keyed by {@link StrategyId}. Built-ins are
 * contributed through {@link StrategyProvider} services on the classpath, so adding a strategy is
 * dropping a jar — the core never changes.
 */
public final class StrategyRegistry {

    private final Map<StrategyId, SortStrategy<?>> byId = new LinkedHashMap<>();

    public StrategyRegistry register(SortStrategy<?> strategy) {
        byId.put(strategy.id(), strategy);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <K> SortStrategy<K> get(StrategyId id) {
        SortStrategy<?> s = byId.get(id);
        if (s == null) {
            throw new NoSuchElementException("No strategy registered: " + id);
        }
        return (SortStrategy<K>) s;
    }

    public boolean contains(StrategyId id) {
        return byId.containsKey(id);
    }

    public Set<StrategyId> ids() {
        return Collections.unmodifiableSet(byId.keySet());
    }

    /** Build a registry from every {@link StrategyProvider} service on the classpath. */
    public static StrategyRegistry withDefaults() {
        StrategyRegistry registry = new StrategyRegistry();
        for (StrategyProvider provider : ServiceLoader.load(StrategyProvider.class)) {
            for (SortStrategy<?> s : provider.strategies()) {
                registry.register(s);
            }
        }
        // Fallback when SPI metadata is not on the classpath (e.g. exploded classes without resources).
        if (registry.byId.isEmpty()) {
            for (SortStrategy<?> s : new BuiltinStrategyProvider().strategies()) {
                registry.register(s);
            }
        }
        return registry;
    }
}
