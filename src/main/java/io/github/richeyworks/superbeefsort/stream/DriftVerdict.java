package io.github.richeyworks.superbeefsort.stream;

/**
 * The outcome of asking a {@link DriftDetector} about one batch: whether drift was declared, the
 * measured drift {@code score} against the live reference, the {@code threshold} it was compared to,
 * and a human-readable {@code reason} (surfaced as the re-selection rationale by
 * {@link AdaptiveStreamSorter}).
 *
 * @param drift     true when the engine should re-profile/re-select for this batch
 * @param score     combined drift distance to the reference in {@code [0,1]} ({@code 0} on the first batch)
 * @param threshold the detector's drift threshold this score was tested against
 * @param reason    short explanation ("initial baseline", "stable", "drift 0.62 >= 0.20", ...)
 */
public record DriftVerdict(boolean drift, double score, double threshold, String reason) {
}
