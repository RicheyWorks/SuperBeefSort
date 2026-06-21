package io.github.richeyworks.sbskernels.rust;

import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.registry.StrategyProvider;

import java.util.List;

/**
 * {@link StrategyProvider} SPI implementation for the native Rust radix kernel.
 *
 * <p>Returns an empty list when the native bridge is unavailable (missing cdylib, unsupported OS,
 * or native access not granted), so the main module's {@code ServiceLoader} loop sees nothing and
 * selection falls through to the pure-Java {@code radix.lsd}. The main module must catch
 * {@link java.util.ServiceConfigurationError} when iterating providers so that an incompatible
 * class-file version (JDK &lt; 22 loading a JDK-22-compiled module) degrades silently too.</p>
 */
public final class RustKernelStrategyProvider implements StrategyProvider {

    @Override
    public List<SortStrategy<?>> strategies() {
        if (!RustRadixBridge.isAvailable()) {
            return List.of();
        }
        return List.of(new RustRadixSortStrategy<>());
    }
}
