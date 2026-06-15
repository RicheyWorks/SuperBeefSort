package io.github.richeyworks.superbeefsort.strategy;

import io.github.richeyworks.superbeefsort.core.SortBuffer;

/**
 * Fixed comparator networks for small ranges - Batcher's odd-even mergesort, generated for the next
 * power of two and trimmed to the exact size. A sorting network is <em>data-oblivious</em>: the same
 * sequence of compare-exchanges sorts every input of that length, with no data-dependent branching.
 * That makes it the ideal branch-light base-case kernel for the recursion leaves of quick/intro/merge
 * (the classic "insertion cutoff", but cheaper and branch-predictable).
 *
 * <p>Every network below was verified <strong>exhaustively</strong> via the 0/1 principle (a network
 * sorts all inputs iff it sorts all {@code 2^n} binary inputs) and cross-checked against the reference
 * sort for {@code n = 2..16}. Comparator counts match Batcher (e.g. n=8 -> 19, n=16 -> 63).</p>
 */
final class SortingNetwork {

    private SortingNetwork() {
    }

    /** Largest range size with a precomputed network. */
    static final int MAX = 16;

    // NET[n] is a flat list of comparator index pairs (i0,j0, i1,j1, ...), each with i < j < n.
    // Applying them in order with a compare-exchange sorts any range of length n. Generated and
    // verified offline (Batcher odd-even mergesort, 0/1-principle checked) - do not hand-edit.
    private static final int[][] NET = {
        {}, {},                                  // 0, 1
        {0,1},  // n=2 (1 comparators)
        {0,1,0,2,1,2},  // n=3 (3 comparators)
        {0,1,2,3,0,2,1,3,1,2},  // n=4 (5 comparators)
        {0,1,2,3,0,2,1,3,1,2,0,4,2,4,1,2,3,4},  // n=5 (9 comparators)
        {0,1,2,3,0,2,1,3,1,2,4,5,0,4,2,4,1,5,3,5,1,2,3,4},  // n=6 (12 comparators)
        {0,1,2,3,0,2,1,3,1,2,4,5,4,6,5,6,0,4,2,6,2,4,1,5,3,5,1,2,3,4,5,6},  // n=7 (16 comparators)
        {0,1,2,3,0,2,1,3,1,2,4,5,6,7,4,6,5,7,5,6,0,4,2,6,2,4,1,5,3,7,3,5,1,2,3,4,5,6},  // n=8 (19 comparators)
        {0,1,2,3,0,2,1,3,1,2,4,5,6,7,4,6,5,7,5,6,0,4,2,6,2,4,1,5,3,7,3,5,1,2,3,4,5,6,0,8,4,8,2,4,6,8,3,5,1,2,3,4,5,6,7,8},  // n=9 (28 comparators)
        {0,1,2,3,0,2,1,3,1,2,4,5,6,7,4,6,5,7,5,6,0,4,2,6,2,4,1,5,3,7,3,5,1,2,3,4,5,6,8,9,0,8,4,8,2,4,6,8,1,9,5,9,3,5,7,9,1,2,3,4,5,6,7,8},  // n=10 (32 comparators)
        {0,1,2,3,0,2,1,3,1,2,4,5,6,7,4,6,5,7,5,6,0,4,2,6,2,4,1,5,3,7,3,5,1,2,3,4,5,6,8,9,8,10,9,10,9,10,0,8,4,8,2,10,6,10,2,4,6,8,1,9,5,9,3,5,7,9,1,2,3,4,5,6,7,8,9,10},  // n=11 (38 comparators)
        {0,1,2,3,0,2,1,3,1,2,4,5,6,7,4,6,5,7,5,6,0,4,2,6,2,4,1,5,3,7,3,5,1,2,3,4,5,6,8,9,10,11,8,10,9,11,9,10,9,10,0,8,4,8,2,10,6,10,2,4,6,8,1,9,5,9,3,11,7,11,3,5,7,9,1,2,3,4,5,6,7,8,9,10},  // n=12 (42 comparators)
        {0,1,2,3,0,2,1,3,1,2,4,5,6,7,4,6,5,7,5,6,0,4,2,6,2,4,1,5,3,7,3,5,1,2,3,4,5,6,8,9,10,11,8,10,9,11,9,10,8,12,10,12,9,10,11,12,0,8,4,12,4,8,2,10,6,10,2,4,6,8,10,12,1,9,5,9,3,11,7,11,3,5,7,9,1,2,3,4,5,6,7,8,9,10,11,12},  // n=13 (48 comparators)
        {0,1,2,3,0,2,1,3,1,2,4,5,6,7,4,6,5,7,5,6,0,4,2,6,2,4,1,5,3,7,3,5,1,2,3,4,5,6,8,9,10,11,8,10,9,11,9,10,12,13,8,12,10,12,9,13,11,13,9,10,11,12,0,8,4,12,4,8,2,10,6,10,2,4,6,8,10,12,1,9,5,13,5,9,3,11,7,11,3,5,7,9,11,13,1,2,3,4,5,6,7,8,9,10,11,12},  // n=14 (53 comparators)
        {0,1,2,3,0,2,1,3,1,2,4,5,6,7,4,6,5,7,5,6,0,4,2,6,2,4,1,5,3,7,3,5,1,2,3,4,5,6,8,9,10,11,8,10,9,11,9,10,12,13,12,14,13,14,8,12,10,14,10,12,9,13,11,13,9,10,11,12,13,14,0,8,4,12,4,8,2,10,6,14,6,10,2,4,6,8,10,12,1,9,5,13,5,9,3,11,7,11,3,5,7,9,11,13,1,2,3,4,5,6,7,8,9,10,11,12,13,14},  // n=15 (59 comparators)
        {0,1,2,3,0,2,1,3,1,2,4,5,6,7,4,6,5,7,5,6,0,4,2,6,2,4,1,5,3,7,3,5,1,2,3,4,5,6,8,9,10,11,8,10,9,11,9,10,12,13,14,15,12,14,13,15,13,14,8,12,10,14,10,12,9,13,11,15,11,13,9,10,11,12,13,14,0,8,4,12,4,8,2,10,6,14,6,10,2,4,6,8,10,12,1,9,5,13,5,9,3,11,7,15,7,11,3,5,7,9,11,13,1,2,3,4,5,6,7,8,9,10,11,12,13,14},  // n=16 (63 comparators)
    };

    /** True when a precomputed network exists for a range of length {@code n}. */
    static boolean handles(int n) {
        return n >= 0 && n <= MAX;
    }

    /**
     * Sort the {@code n} elements starting at index {@code lo} in place using the precomputed network.
     * Caller must ensure {@code handles(n)} and that {@code [lo, lo+n)} is in bounds.
     */
    static <K> void sort(SortBuffer<K> b, int lo, int n) {
        if (n <= 1) {
            return;
        }
        int[] net = NET[n];
        for (int k = 0; k < net.length; k += 2) {
            int i = lo + net[k];
            int j = lo + net[k + 1];
            if (b.compare(i, j) > 0) {   // compare-exchange: keep the smaller key at the lower index
                b.swap(i, j);
            }
        }
    }
}
   