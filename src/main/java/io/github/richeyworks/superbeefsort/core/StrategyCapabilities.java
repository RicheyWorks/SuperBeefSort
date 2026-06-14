package io.github.richeyworks.superbeefsort.core;

/**
 * Declarative metadata the selector reasons over when choosing a strategy for a given data profile.
 * Capability-driven selection is what lets new strategies slot in without the selector hard-coding
 * their names — e.g. {@code requiresIntegerKeys} gates the non-comparison sorts.
 */
public record StrategyCapabilities(
        boolean stable,
        boolean inPlace,
        boolean comparisonBased,
        boolean adaptive,
        boolean parallel,
        boolean requiresIntegerKeys,
        Runtime backingRuntime) {

    /** Which runtime actually executes the kernel. Phase 0/1 is all {@code JAVA}. */
    public enum Runtime { JAVA, RUST, PYTHON }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean stable = false;
        private boolean inPlace = true;
        private boolean comparisonBased = true;
        private boolean adaptive = false;
        private boolean parallel = false;
        private boolean requiresIntegerKeys = false;
        private Runtime backingRuntime = Runtime.JAVA;

        public Builder stable(boolean v) { this.stable = v; return this; }
        public Builder inPlace(boolean v) { this.inPlace = v; return this; }
        public Builder comparisonBased(boolean v) { this.comparisonBased = v; return this; }
        public Builder adaptive(boolean v) { this.adaptive = v; return this; }
        public Builder parallel(boolean v) { this.parallel = v; return this; }
        public Builder requiresIntegerKeys(boolean v) { this.requiresIntegerKeys = v; return this; }
        public Builder backingRuntime(Runtime v) { this.backingRuntime = v; return this; }

        public StrategyCapabilities build() {
            return new StrategyCapabilities(
                    stable, inPlace, comparisonBased, adaptive, parallel, requiresIntegerKeys, backingRuntime);
        }
    }
}
