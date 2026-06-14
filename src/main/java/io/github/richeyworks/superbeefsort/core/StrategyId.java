package io.github.richeyworks.superbeefsort.core;

import java.util.Objects;

/** Stable identifier for a {@link SortStrategy} (e.g. {@code "quick.threeway"}, {@code "radix.lsd.rust"}). */
public final class StrategyId {

    private final String value;

    private StrategyId(String value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static StrategyId of(String value) {
        return new StrategyId(value);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return (o instanceof StrategyId other) && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
