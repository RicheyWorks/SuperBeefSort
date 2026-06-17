package io.github.richeyworks.superbeefsort.strategy;

/**
 * The entropy-aware plan for an LSD radix sort: how many bits to consume per pass and how many passes that
 * implies to cover the {@code significantBits} that actually vary across the keys. Choosing the base is a
 * trade-off — a wider base (more bits per pass) means fewer passes but a larger per-pass count array
 * ({@code 2^bitsPerPass}). {@link #forWidth} picks the base that minimizes the modeled total work
 * {@code passes * (n + 2^bitsPerPass)}, so a few bits of range over many items take one wide pass while
 * full-width keys spread over several narrower ones — instead of a fixed 8-bit/8-pass schedule.
 *
 * <p>Pure and tiny, so the decision is unit-testable in isolation from the sort.</p>
 *
 * @param bitsPerPass bits consumed per counting pass ({@code 1..}{@value #MAX_BITS})
 * @param passes      number of passes ({@code 0} when there is nothing to distinguish)
 */
public record RadixPlan(int bitsPerPass, int passes) {

    /** Cap on bits per pass: a {@code 2^16}-entry count array is the largest we'll allocate per pass. */
    public static final int MAX_BITS = 16;

    public int radix() {
        return 1 << bitsPerPass;
    }

    public int mask() {
        return (1 << bitsPerPass) - 1;
    }

    /**
     * Choose the cheapest plan to sort {@code n} items whose keys span {@code significantBits} varying bits.
     * {@code significantBits <= 0} means all keys are equal — zero passes.
     */
    public static RadixPlan forWidth(int significantBits, int n) {
        if (significantBits <= 0) {
            return new RadixPlan(1, 0);
        }
        long items = Math.max(n, 1);
        int bestBits = 1;
        double bestCost = Double.MAX_VALUE;
        for (int b = 1; b <= MAX_BITS; b++) {
            int passes = (significantBits + b - 1) / b;
            double cost = (double) passes * (items + (1L << b)); // ~ work per pass: scan n + touch 2^b buckets
            if (cost < bestCost) {
                bestCost = cost;
                bestBits = b;
            }
        }
        return new RadixPlan(bestBits, (significantBits + bestBits - 1) / bestBits);
    }
}
