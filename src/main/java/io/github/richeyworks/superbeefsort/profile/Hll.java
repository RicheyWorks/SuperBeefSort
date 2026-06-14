package io.github.richeyworks.superbeefsort.profile;

/**
 * Minimal HyperLogLog (p = 11, 2048 registers) for estimating distinct-key count in one pass with
 * ~2.3% standard error and a few KB of state. Hashes are expected to be well-mixed 64-bit values.
 */
final class Hll {

    private static final int P = 11;
    private static final int M = 1 << P;

    private final byte[] registers = new byte[M];

    void add(long hash) {
        int index = (int) (hash >>> (64 - P));
        long w = (hash << P) | (1L << (P - 1)); // guard bit bounds the rank
        int rank = Long.numberOfLeadingZeros(w) + 1;
        if (rank > registers[index]) {
            registers[index] = (byte) rank;
        }
    }

    long estimate() {
        double alpha = 0.7213 / (1.0 + 1.079 / M);
        double sum = 0.0;
        int zeros = 0;
        for (byte r : registers) {
            sum += 1.0 / (1L << r);
            if (r == 0) {
                zeros++;
            }
        }
        double estimate = alpha * M * M / sum;
        if (estimate <= 2.5 * M && zeros > 0) {
            estimate = M * Math.log((double) M / zeros); // linear counting for small cardinalities
        }
        return Math.round(estimate);
    }
}
