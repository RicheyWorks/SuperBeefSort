package io.github.richeyworks.superbeefsort.feed;

/** Outcome of feeding a sorted run into CSRBT. */
public record FeedResult(
        FeedMode mode,
        int presented,
        int inserted,
        int duplicates,
        int healthChecks,
        boolean healthy,
        long elapsedNanos) {

    public double elapsedMillis() {
        return elapsedNanos / 1_000_000.0;
    }
}
