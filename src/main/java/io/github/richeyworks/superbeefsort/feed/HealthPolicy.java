package io.github.richeyworks.superbeefsort.feed;

/**
 * Governs how an incremental feeder (today the {@link StreamingFeeder}) trades throughput against CSRBT's
 * self-healing guarantees. Feeding proceeds in batches of {@code batchSize} {@code add}s; after every
 * {@code validateEvery} batches the feeder asks the target to validate-and-repair itself
 * ({@code CsrbtTarget.checkHealth()} → CSRBT {@code selfRepair}). This is the "backpressure" of the
 * streaming path: a {@code validateEvery} of {@code 1} heals after every batch (safest), a larger value
 * amortizes the check, and {@code 0} disables periodic validation for maximum throughput.
 *
 * @param batchSize     adds per batch before a validation checkpoint is considered ({@code >= 1})
 * @param validateEvery validate-and-repair every N batches; {@code 0} never validates ({@code >= 0})
 */
public record HealthPolicy(int batchSize, int validateEvery) {

    public HealthPolicy {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be >= 1");
        }
        if (validateEvery < 0) {
            throw new IllegalArgumentException("validateEvery must be >= 0");
        }
    }

    /** 1024-add batches, validating after every batch — the safe, self-healing default. */
    public static HealthPolicy defaults() {
        return new HealthPolicy(1024, 1);
    }

    /** Throughput-first: {@code batchSize} batches with no periodic validation. */
    public static HealthPolicy throughput(int batchSize) {
        return new HealthPolicy(batchSize, 0);
    }
}
