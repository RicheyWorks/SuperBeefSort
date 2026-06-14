package io.github.richeyworks.superbeefsort.registry;

import io.github.richeyworks.superbeefsort.core.SortStrategy;

import java.util.List;

/**
 * Service-provider interface for contributing strategies via {@link java.util.ServiceLoader}.
 * A new algorithm pack drops onto the classpath with its own provider and is discovered
 * automatically — the engine core never changes.
 */
public interface StrategyProvider {

    List<SortStrategy<?>> strategies();
}
