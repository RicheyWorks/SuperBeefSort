package io.github.richeyworks.superbeefsort.csrbt;

/**
 * The expected access pattern for a CSRBT set SuperBeefSort builds/feeds. It drives {@link StrategyAdvisor},
 * which picks the balancing strategy the tree is born with ("construct into the right shape" — see
 * docs/architecture-csrbt-integration.md). {@link #BALANCED} is the default and maps to red-black, so
 * declaring nothing changes nothing.
 */
public enum AccessPolicy {
    /** Mixed reads and writes — the safe general default (RedBlack: few rotations, ≤2× height). */
    BALANCED,
    /** Lookups dominate — favour the shortest trees (AVL: strict balance). */
    READ_HEAVY,
    /** Inserts/removes dominate — favour fewer rebalances (weight-balanced BB[α]). */
    WRITE_HEAVY,
    /** A few hot keys / temporal locality — favour self-adjustment (Splay). */
    SKEWED
}
