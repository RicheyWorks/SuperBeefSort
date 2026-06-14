package io.github.richeyworks.superbeefsort.core;

import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/**
 * Maps a key {@code K} to a {@code long} integer key so non-comparison sorts (counting, radix) can
 * be used. The encoding <strong>must be order-preserving with respect to the comparator the data is
 * sorted under</strong>: for all {@code a}, {@code b}, if {@code comparator.compare(a, b) < 0} then
 * {@code encodeKey(a) < encodeKey(b)}. A faithful encoder lets the engine sort in linear time; an
 * inconsistent one would reorder keys, so the profiler validates monotonicity on a sample and falls
 * back to comparison sorts if the encoder does not hold up.
 */
@FunctionalInterface
public interface KeyEncoder<K> {

    long encodeKey(K value);

    /** Encoder from a 32-bit key extractor (e.g. {@code KeyEncoder.ofInt(i -> i)} for {@code Integer}). */
    static <K> KeyEncoder<K> ofInt(ToIntFunction<? super K> extractor) {
        return value -> extractor.applyAsInt(value);
    }

    /** Encoder from a 64-bit key extractor. */
    static <K> KeyEncoder<K> ofLong(ToLongFunction<? super K> extractor) {
        return extractor::applyAsLong;
    }
}
