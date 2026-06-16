package io.github.richeyworks.superbeefsort.core;

import java.util.Comparator;

/**
 * Exposes a key as a sequence of unsigned bytes for MSD radix sorting — the abstraction that lets
 * {@link io.github.richeyworks.superbeefsort.strategy.MsdRadixSortStrategy} sort variable-length keys
 * (strings, byte arrays) that have no faithful single-{@code long} encoding, and so cannot use the
 * counting / LSD-radix path that {@link KeyEncoder} feeds.
 *
 * <p>Radix order is the lexicographic order of these byte sequences with a shorter sequence (a prefix)
 * ordering before its extensions. An encoder is <em>faithful</em> to a comparator when that byte order
 * equals the comparator's order; {@link #forStrings()} is faithful to {@link String}'s natural order,
 * and {@link #forByteArrays()} is faithful to {@link #byteArrayComparator()}.</p>
 *
 * @param <K> the key type
 */
public interface ByteSequenceEncoder<K> {

    /** Number of bytes in {@code key}'s sequence. */
    int length(K key);

    /** The unsigned byte ({@code 0..255}) at {@code index}, for {@code 0 <= index < length(key)}. */
    int byteAt(K key, int index);

    /**
     * Views a {@link String} as its big-endian UTF-16 bytes (high byte then low byte of each
     * {@code char}). Because Java's {@link String#compareTo} also orders by UTF-16 code unit with
     * shorter-prefix-first, MSD radix over this view reproduces {@code String} natural order exactly —
     * including surrogate pairs, which both orderings treat as their UTF-16 units.
     */
    static ByteSequenceEncoder<String> forStrings() {
        return new ByteSequenceEncoder<>() {
            @Override
            public int length(String key) {
                return key.length() << 1;
            }

            @Override
            public int byteAt(String key, int index) {
                char c = key.charAt(index >> 1);
                return ((index & 1) == 0) ? (c >>> 8) & 0xFF : c & 0xFF;
            }
        };
    }

    /** Views a {@code byte[]} as its unsigned bytes; faithful to {@link #byteArrayComparator()}. */
    static ByteSequenceEncoder<byte[]> forByteArrays() {
        return new ByteSequenceEncoder<>() {
            @Override
            public int length(byte[] key) {
                return key.length;
            }

            @Override
            public int byteAt(byte[] key, int index) {
                return key[index] & 0xFF;
            }
        };
    }

    /** Unsigned lexicographic order over {@code byte[]} (shorter prefix first) — matches {@link #forByteArrays()}. */
    static Comparator<byte[]> byteArrayComparator() {
        return (x, y) -> {
            int m = Math.min(x.length, y.length);
            for (int i = 0; i < m; i++) {
                int d = (x[i] & 0xFF) - (y[i] & 0xFF);
                if (d != 0) {
                    return d;
                }
            }
            return Integer.compare(x.length, y.length);
        };
    }
}
