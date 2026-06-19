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
        boolean requiresByteSequenceEncoder,
        Runtime backingRuntime,
        AuxMemory auxMemory) {

    /** Which runtime actually executes the kernel. Phase 0/1 is all {@code JAVA}. */
    public enum Runtime { JAVA, RUST, PYTHON }

    /**
     * Auxiliary-memory growth class the selector reasons over. {@link #inPlace} is the coarse boolean;
     * this is the finer signal a memory-budgeted objective needs — it is what separates plain merge
     * ({@code LINEAR}, O(n) scratch) from the stable in-place WikiSort ({@code CONSTANT}), the distinction
     * that lets the cost model prefer WikiSort once n makes merge's O(n) buffer prohibitive.
     */
    public enum AuxMemory {
        /** O(1) extra space (in-place): insertion, heap, network, merge.inplace, merge.wiki. */
        CONSTANT,
        /** O(log n) extra space (recursion stack): introsort, quicksort. */
        LOGARITHMIC,
        /** O(n) extra space (scratch buffers / count arrays): merge, counting, radix, learned, TimSort. */
        LINEAR;

        /** Estimated peak auxiliary bytes for {@code n} object references (8 B each) under this class. */
        public long estimatedBytes(long n) {
            long refBytes = 8L;
            long nn = Math.max(0L, n);
            return switch (this) {
                case CONSTANT -> 0L;
                case LOGARITHMIC -> refBytes * (long) Math.ceil(Math.log(Math.max(2L, nn)) / Math.log(2));
                case LINEAR -> refBytes * nn;
            };
        }
    }

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
        private boolean requiresByteSequenceEncoder = false;
        private Runtime backingRuntime = Runtime.JAVA;
        private AuxMemory auxMemory = AuxMemory.CONSTANT;   // most strategies sort in place

        public Builder stable(boolean v) { this.stable = v; return this; }
        public Builder inPlace(boolean v) { this.inPlace = v; return this; }
        public Builder comparisonBased(boolean v) { this.comparisonBased = v; return this; }
        public Builder adaptive(boolean v) { this.adaptive = v; return this; }
        public Builder parallel(boolean v) { this.parallel = v; return this; }
        public Builder requiresIntegerKeys(boolean v) { this.requiresIntegerKeys = v; return this; }
        public Builder requiresByteSequenceEncoder(boolean v) { this.requiresByteSequenceEncoder = v; return this; }
        public Builder backingRuntime(Runtime v) { this.backingRuntime = v; return this; }
        public Builder auxMemory(AuxMemory v) { this.auxMemory = v; return this; }

        public StrategyCapabilities build() {
            return new StrategyCapabilities(
                    stable, inPlace, comparisonBased, adaptive, parallel,
                    requiresIntegerKeys, requiresByteSequenceEncoder, backingRuntime, auxMemory);
        }
    }
}
