"""
sbs_mirror.py — a faithful Python mirror of the SuperBeefSort profiler + candidate
strategies + brute-force oracle, used to (a) validate against the shipped
Phase4DecisionGate's published oracle spread and (b) generate a labeled training
corpus for the Phase 4 LearnedModelStrategySelector.

Fidelity target (docs/adr-phase4-python-intelligence.md, action item 2):
  Oracle winner spread over the gate's 324 workloads:
    counting 138, jdk.timsort 108, insertion 48, intro 30.

Everything here mirrors specific Java source:
  - java.util.Random (LCG) so the gate's seeded workloads reproduce bit-for-bit
  - SortBuffer metering: compare()/compareToKey()/compareValues() => +1 comparison;
    swap() => +2 moves; recordMove() => +1 move; set() is NOT metered.
  - strategy/*.java operation sequences (insertion, merge, heap, intro+network,
    jdk.timsort, counting, radix.lsd, learned)
  - profile/IntelligentDataProfiler.java feature computation + oracle candidate gating
"""

from __future__ import annotations

import bisect
import functools
import sys
from dataclasses import dataclass

MASK48 = (1 << 48) - 1
MULT = 0x5DEECE66D
ADD = 0xB


def _to_int32(v: int) -> int:
    v &= 0xFFFFFFFF
    return v - (1 << 32) if v >= (1 << 31) else v


class JavaRandom:
    """Bit-exact port of java.util.Random (used only for seeded shape generation)."""

    def __init__(self, seed: int):
        self.seed = (seed ^ MULT) & MASK48

    def _next(self, bits: int) -> int:
        self.seed = (self.seed * MULT + ADD) & MASK48
        return _to_int32(self.seed >> (48 - bits))

    def next_int(self, bound: int) -> int:
        if bound <= 0:
            raise ValueError("bound must be positive")
        if (bound & -bound) == bound:  # power of two
            return (bound * self._next(31)) >> 31
        while True:
            bits = self._next(31)
            val = bits % bound
            # Java's signed-overflow rejection: while (bits - val + (bound-1) < 0)
            if _to_int32(bits - val + (bound - 1)) >= 0:
                return val


# --------------------------------------------------------------------------- #
# Workload generators — mirror Phase4DecisionGate.generate(...)
# --------------------------------------------------------------------------- #

SHAPES = [
    "sorted", "reversed", "all_equal", "nearly_sorted",
    "sawtooth", "organ_pipe", "few_distinct", "random", "clustered",
]
SHAPE_ORDINAL = {s: i for i, s in enumerate(SHAPES)}


def generate(shape: str, n: int, seed: int) -> list[int]:
    if shape == "sorted":
        return list(range(n))
    if shape == "reversed":
        return list(range(n - 1, -1, -1))
    if shape == "all_equal":
        return [42] * n
    if shape == "nearly_sorted":
        a = list(range(n))
        rng = JavaRandom(seed)
        swaps = max(1, n // 20)
        for _ in range(swaps):
            i = rng.next_int(n)
            j = rng.next_int(n)
            a[i], a[j] = a[j], a[i]
        return a
    if shape == "sawtooth":
        period = 8
        return [i % period for i in range(n)]
    if shape == "organ_pipe":
        return [min(i, n - 1 - i) for i in range(n)]
    if shape == "few_distinct":
        distinct = 4
        return [i % distinct for i in range(n)]
    if shape == "random":
        rng = JavaRandom(seed)
        bound = max(1, 2 * n)
        return [rng.next_int(bound) for _ in range(n)]
    if shape == "clustered":
        rng = JavaRandom(seed)
        clusters = max(4, n // 20)
        return [rng.next_int(clusters) * 100 + rng.next_int(10) for _ in range(n)]
    raise ValueError(shape)


def gate_seed(shape: str, n: int, trial: int, with_keys: bool) -> int:
    return SHAPE_ORDINAL[shape] * 137 + n * 31 + trial + (0 if with_keys else 999_999)


# --------------------------------------------------------------------------- #
# Metered strategies — each returns (comparisons, moves). Mirror strategy/*.java.
# Inputs are plain int lists (identity KeyEncoder for Integer => key == value).
# --------------------------------------------------------------------------- #

def _timsort_comparisons(values: list) -> int:
    """CPython list.sort is TimSort; count comparisons via a cmp wrapper.
    A faithful proxy for Java's Arrays/Collections TimSort comparison count."""
    cnt = 0

    def cmp(x, y):
        nonlocal cnt
        cnt += 1
        return -1 if x < y else (1 if x > y else 0)

    sorted(values, key=functools.cmp_to_key(cmp))
    return cnt


def cost_insertion(a: list[int]) -> tuple[int, int]:
    comps = moves = 0
    n = len(a)
    a = a[:]
    for i in range(1, n):
        key = a[i]
        j = i - 1
        while j >= 0:
            comps += 1                 # compareToKey
            if a[j] > key:
                a[j + 1] = a[j]
                moves += 1             # recordMove
                j -= 1
            else:
                break
        a[j + 1] = key
    return comps, moves


def cost_merge(a: list[int]) -> tuple[int, int]:
    n = len(a)
    if n < 2:
        return 0, 0
    work = a[:]
    aux = a[:]
    comps = 0
    moves = 0

    def msort(lo: int, hi: int):
        nonlocal comps, moves
        if hi - lo < 2:
            return
        mid = (lo + hi) // 2
        msort(lo, mid)
        msort(mid, hi)
        i, j, k = lo, mid, lo
        while i < mid and j < hi:
            comps += 1                 # compareValues(a[j], a[i])
            if work[j] < work[i]:
                aux[k] = work[j]; j += 1
            else:
                aux[k] = work[i]; i += 1
            k += 1
            moves += 1
        while i < mid:
            aux[k] = work[i]; i += 1; k += 1; moves += 1
        while j < hi:
            aux[k] = work[j]; j += 1; k += 1; moves += 1
        for t in range(lo, hi):
            work[t] = aux[t]

    old = sys.getrecursionlimit()
    sys.setrecursionlimit(max(old, 4 * n + 100))
    try:
        msort(0, n)
    finally:
        sys.setrecursionlimit(old)
    return comps, moves


def cost_heap(a: list[int]) -> tuple[int, int]:
    a = a[:]
    n = len(a)
    comps = 0
    moves = 0

    def sift(root: int, end: int):
        nonlocal comps, moves
        while True:
            child = 2 * root + 1
            if child >= end:
                break
            if child + 1 < end:
                comps += 1
                if a[child + 1] > a[child]:
                    child += 1
            comps += 1
            if a[child] > a[root]:
                a[root], a[child] = a[child], a[root]
                moves += 2
                root = child
            else:
                break

    for i in range(n // 2 - 1, -1, -1):
        sift(i, n)
    for end in range(n - 1, 0, -1):
        a[0], a[end] = a[end], a[0]
        moves += 2
        sift(0, end)
    return comps, moves


# Batcher odd-even networks, copied verbatim from strategy/SortingNetwork.java NET[].
_NET = {
    2: [0, 1],
    3: [0, 1, 0, 2, 1, 2],
    4: [0, 1, 2, 3, 0, 2, 1, 3, 1, 2],
    5: [0, 1, 2, 3, 0, 2, 1, 3, 1, 2, 0, 4, 2, 4, 1, 2, 3, 4],
    6: [0, 1, 2, 3, 0, 2, 1, 3, 1, 2, 4, 5, 0, 4, 2, 4, 1, 5, 3, 5, 1, 2, 3, 4],
    7: [0, 1, 2, 3, 0, 2, 1, 3, 1, 2, 4, 5, 4, 6, 5, 6, 0, 4, 2, 6, 2, 4, 1, 5, 3, 5, 1, 2, 3, 4, 5, 6],
    8: [0, 1, 2, 3, 0, 2, 1, 3, 1, 2, 4, 5, 6, 7, 4, 6, 5, 7, 5, 6, 0, 4, 2, 6, 2, 4, 1, 5, 3, 7, 3, 5, 1, 2, 3, 4, 5, 6],
    9: [0, 1, 2, 3, 0, 2, 1, 3, 1, 2, 4, 5, 6, 7, 4, 6, 5, 7, 5, 6, 0, 4, 2, 6, 2, 4, 1, 5, 3, 7, 3, 5, 1, 2, 3, 4, 5, 6, 0, 8, 4, 8, 2, 4, 6, 8, 3, 5, 1, 2, 3, 4, 5, 6, 7, 8],
    10: [0, 1, 2, 3, 0, 2, 1, 3, 1, 2, 4, 5, 6, 7, 4, 6, 5, 7, 5, 6, 0, 4, 2, 6, 2, 4, 1, 5, 3, 7, 3, 5, 1, 2, 3, 4, 5, 6, 8, 9, 0, 8, 4, 8, 2, 4, 6, 8, 1, 9, 5, 9, 3, 5, 7, 9, 1, 2, 3, 4, 5, 6, 7, 8],
    11: [0, 1, 2, 3, 0, 2, 1, 3, 1, 2, 4, 5, 6, 7, 4, 6, 5, 7, 5, 6, 0, 4, 2, 6, 2, 4, 1, 5, 3, 7, 3, 5, 1, 2, 3, 4, 5, 6, 8, 9, 8, 10, 9, 10, 9, 10, 0, 8, 4, 8, 2, 10, 6, 10, 2, 4, 6, 8, 1, 9, 5, 9, 3, 5, 7, 9, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
    12: [0, 1, 2, 3, 0, 2, 1, 3, 1, 2, 4, 5, 6, 7, 4, 6, 5, 7, 5, 6, 0, 4, 2, 6, 2, 4, 1, 5, 3, 7, 3, 5, 1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 8, 10, 9, 11, 9, 10, 9, 10, 0, 8, 4, 8, 2, 10, 6, 10, 2, 4, 6, 8, 1, 9, 5, 9, 3, 11, 7, 11, 3, 5, 7, 9, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
    13: [0, 1, 2, 3, 0, 2, 1, 3, 1, 2, 4, 5, 6, 7, 4, 6, 5, 7, 5, 6, 0, 4, 2, 6, 2, 4, 1, 5, 3, 7, 3, 5, 1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 8, 10, 9, 11, 9, 10, 8, 12, 10, 12, 9, 10, 11, 12, 0, 8, 4, 12, 4, 8, 2, 10, 6, 10, 2, 4, 6, 8, 10, 12, 1, 9, 5, 9, 3, 11, 7, 11, 3, 5, 7, 9, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12],
    14: [0, 1, 2, 3, 0, 2, 1, 3, 1, 2, 4, 5, 6, 7, 4, 6, 5, 7, 5, 6, 0, 4, 2, 6, 2, 4, 1, 5, 3, 7, 3, 5, 1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 8, 10, 9, 11, 9, 10, 12, 13, 8, 12, 10, 12, 9, 13, 11, 13, 9, 10, 11, 12, 0, 8, 4, 12, 4, 8, 2, 10, 6, 10, 2, 4, 6, 8, 10, 12, 1, 9, 5, 13, 5, 9, 3, 11, 7, 11, 3, 5, 7, 9, 11, 13, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12],
    15: [0, 1, 2, 3, 0, 2, 1, 3, 1, 2, 4, 5, 6, 7, 4, 6, 5, 7, 5, 6, 0, 4, 2, 6, 2, 4, 1, 5, 3, 7, 3, 5, 1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 8, 10, 9, 11, 9, 10, 12, 13, 12, 14, 13, 14, 8, 12, 10, 14, 10, 12, 9, 13, 11, 13, 9, 10, 11, 12, 13, 14, 0, 8, 4, 12, 4, 8, 2, 10, 6, 14, 6, 10, 2, 4, 6, 8, 10, 12, 1, 9, 5, 13, 5, 9, 3, 11, 7, 11, 3, 5, 7, 9, 11, 13, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14],
    16: [0, 1, 2, 3, 0, 2, 1, 3, 1, 2, 4, 5, 6, 7, 4, 6, 5, 7, 5, 6, 0, 4, 2, 6, 2, 4, 1, 5, 3, 7, 3, 5, 1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 8, 10, 9, 11, 9, 10, 12, 13, 14, 15, 12, 14, 13, 15, 13, 14, 8, 12, 10, 14, 10, 12, 9, 13, 11, 15, 11, 13, 9, 10, 11, 12, 13, 14, 0, 8, 4, 12, 4, 8, 2, 10, 6, 14, 6, 10, 2, 4, 6, 8, 10, 12, 1, 9, 5, 13, 5, 9, 3, 11, 7, 15, 7, 11, 3, 5, 7, 9, 11, 13, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14],
}
_CUTOFF = 16


def cost_intro(a: list[int]) -> tuple[int, int]:
    a = a[:]
    n = len(a)
    if n < 2:
        return 0, 0
    comps = 0
    moves = 0

    def med3(x: int, y: int, z: int) -> int:
        nonlocal comps
        comps += 1
        if a[x] < a[y]:
            comps += 1
            if a[y] < a[z]:
                return y
            comps += 1
            return z if a[x] < a[z] else x
        else:
            comps += 1
            if a[z] < a[y]:
                return y
            comps += 1
            return z if a[z] < a[x] else x

    def network(lo: int, sz: int):
        nonlocal comps, moves
        if sz <= 1:
            return
        net = _NET[sz]
        for k in range(0, len(net), 2):
            i = lo + net[k]
            j = lo + net[k + 1]
            comps += 1
            if a[i] > a[j]:
                a[i], a[j] = a[j], a[i]
                moves += 2

    def sift(lo: int, root: int, end: int):
        nonlocal comps, moves
        while True:
            child = 2 * root + 1
            if child >= end:
                break
            if child + 1 < end:
                comps += 1
                if a[lo + child + 1] > a[lo + child]:
                    child += 1
            comps += 1
            if a[lo + child] > a[lo + root]:
                a[lo + root], a[lo + child] = a[lo + child], a[lo + root]
                moves += 2
                root = child
            else:
                break

    def heap_range(lo: int, hi: int):
        nonlocal moves
        m = hi - lo + 1
        for i in range(m // 2 - 1, -1, -1):
            sift(lo, i, m)
        for end in range(m - 1, 0, -1):
            a[lo], a[lo + end] = a[lo + end], a[lo]
            moves += 2
            sift(lo, 0, end)

    def introsort(lo: int, hi: int, depth: int):
        nonlocal comps, moves
        while hi - lo >= _CUTOFF:
            if depth == 0:
                heap_range(lo, hi)
                return
            depth -= 1
            mid = lo + ((hi - lo) >> 1)
            p = med3(lo, mid, hi)
            a[lo], a[p] = a[p], a[lo]
            moves += 2
            pivot = a[lo]
            lt, gt, i = lo, hi, lo + 1
            while i <= gt:
                comps += 1                 # compareToKey(i, pivot)
                c = -1 if a[i] < pivot else (1 if a[i] > pivot else 0)
                if c < 0:
                    a[lt], a[i] = a[i], a[lt]; moves += 2; lt += 1; i += 1
                elif c > 0:
                    a[i], a[gt] = a[gt], a[i]; moves += 2; gt -= 1
                else:
                    i += 1
            introsort(lo, lt - 1, depth)
            lo = gt + 1
        network(lo, hi - lo + 1)

    depth_limit = 2 * (n.bit_length() - 1)  # 2 * floor(log2 n)
    introsort(0, n - 1, depth_limit)
    return comps, moves


def cost_jdk_timsort(a: list[int]) -> tuple[int, int]:
    return _timsort_comparisons(a), len(a)


def cost_counting(a: list[int]) -> tuple[int, int]:
    return 0, len(a)


def cost_radix(a: list[int]) -> tuple[int, int]:
    return 0, len(a)


# learned-sort constants (strategy/LearnedSortStrategy.java)
_TARGET_BUCKET = 64
_MAX_BUCKETS = 1 << 12
_OVERSAMPLE = 8


def cost_learned(a: list[int]) -> tuple[int, int]:
    n = len(a)
    if n < 2:
        return 0, 0
    keys = a  # identity encoder
    buckets = max(2, min(_MAX_BUCKETS, n // _TARGET_BUCKET))

    sample_size = min(n, buckets * _OVERSAMPLE)
    sample = [keys[(k * n) // sample_size] for k in range(sample_size)]
    sample.sort()
    splitters = [sample[((s + 1) * sample_size) // buckets] for s in range(buckets - 1)]

    # bucketIndex = number of splitters <= key == bisect_right(splitters, key)
    bucket_elems: list[list[int]] = [[] for _ in range(buckets)]
    for v in a:
        bk = bisect.bisect_right(splitters, v)
        bucket_elems[bk].append(v)

    comps = 0
    for elems in bucket_elems:
        if len(elems) > 1:
            comps += _timsort_comparisons(elems)
    return comps, n


# --------------------------------------------------------------------------- #
# Profiler — mirror IntelligentDataProfiler.profile(buf, SHALLOW)
# --------------------------------------------------------------------------- #

_COUNTING_RANGE_CAP = 1 << 24
_INVERSION_EXACT_MAX = 1 << 13   # 8192
_INVERSION_SAMPLE = 1 << 11      # 2048
_HIST_BUCKETS = 16


def _merge_count(a: list[int]) -> int:
    """Bottom-up merge sort counting strict inversions (ties are not inversions)."""
    n = len(a)
    if n < 2:
        return 0
    src = a[:]
    tmp = [0] * n
    inv = 0
    width = 1
    while width < n:
        lo = 0
        step = width << 1
        while lo < n:
            mid = min(lo + width, n)
            hi = min(lo + step, n)
            i, j, k = lo, mid, lo
            while i < mid and j < hi:
                if src[i] <= src[j]:
                    tmp[k] = src[i]; i += 1
                else:
                    tmp[k] = src[j]; j += 1
                    inv += (mid - i)
                k += 1
            while i < mid:
                tmp[k] = src[i]; i += 1; k += 1
            while j < hi:
                tmp[k] = src[j]; j += 1; k += 1
            lo += step
        src, tmp = tmp, src
        width <<= 1
    return inv


def _inversions(a: list[int], depth_deep: bool = False) -> tuple[int, bool]:
    n = len(a)
    exact = depth_deep or n <= _INVERSION_EXACT_MAX
    if exact:
        return _merge_count(a), True
    m = _INVERSION_SAMPLE
    if m >= n:
        return _merge_count(a), True
    sample = [a[(k * n) // m] for k in range(m)]
    sample_inv = _merge_count(sample)
    max_sample_pairs = m * (m - 1) / 2.0
    max_inv = n * (n - 1) / 2.0
    if max_sample_pairs <= 0:
        return 0, False
    return round(sample_inv / max_sample_pairs * max_inv), False


def _classify(hist: list[int], n: int) -> str:
    buckets = len(hist)
    mean = n / buckets
    var = sum((c - mean) ** 2 for c in hist) / buckets
    cv = (var ** 0.5) / mean if mean else 0.0
    if cv < 0.30:
        return "UNIFORM"
    if cv < 1.0:
        return "SKEWED"
    return "CLUSTERED"


@dataclass
class Profile:
    size: int
    sortedness_ratio: float
    has_duplicates: bool
    depth: str
    distinct_estimate: int
    key_min: int | None
    key_max: int | None
    key_span: int | None
    counting_feasible: bool | None
    distribution: str
    longest_run: int
    inversions: int
    inversions_exact: bool
    has_byte_key: bool
    has_key_stats: bool


def profile(a: list[int], with_keys: bool) -> Profile:
    n = len(a)
    if n < 2:
        return Profile(n, 1.0, False, "SHALLOW", n, None, None, None, None,
                       "UNKNOWN", n, 0, True, False, False)

    in_order = 0
    duplicates = False
    cur_run = 1
    longest_run = 1
    for i in range(1, n):
        prev, cur = a[i - 1], a[i]
        c = -1 if prev < cur else (1 if prev > cur else 0)
        if c <= 0:
            in_order += 1
            cur_run += 1
            if cur_run > longest_run:
                longest_run = cur_run
        else:
            cur_run = 1
        if c == 0:
            duplicates = True
    ratio = in_order / (n - 1)

    inversions, inv_exact = _inversions(a)
    distinct_estimate = len(set(a))  # mirror stands in exact distinct for the HLL estimate

    if not with_keys:
        return Profile(n, ratio, duplicates, "SHALLOW", distinct_estimate,
                       None, None, None, None, "UNKNOWN", longest_run,
                       inversions, inv_exact, False, False)

    kmin, kmax = min(a), max(a)          # identity encoder (Integer)
    span = kmax - kmin
    counting_feasible = 0 <= span < _COUNTING_RANGE_CAP

    if span <= 0:
        distribution = "CLUSTERED"
    else:
        hist = [0] * _HIST_BUCKETS
        for k in a:
            b = ((k - kmin) * _HIST_BUCKETS) // (span + 1)
            b = min(max(b, 0), _HIST_BUCKETS - 1)
            hist[b] += 1
        distribution = _classify(hist, n)

    return Profile(n, ratio, duplicates, "SHALLOW", distinct_estimate,
                   kmin, kmax, span, counting_feasible, distribution,
                   longest_run, inversions, inv_exact, False, True)


# --------------------------------------------------------------------------- #
# Oracle — mirror Phase4DecisionGate.oracleCandidates + argmin (first-wins ties)
# --------------------------------------------------------------------------- #

_COST_FN = {
    "insertion": cost_insertion,
    "merge": cost_merge,
    "heap": cost_heap,
    "intro": cost_intro,
    "jdk.timsort": cost_jdk_timsort,
    "counting": cost_counting,
    "radix.lsd": cost_radix,
    "learned": cost_learned,
}


def oracle_candidates(n: int, p: Profile) -> list[str]:
    c = []
    if n <= 1000 or (p.inversions_exact and p.inversions <= 2 * n):
        c.append("insertion")
    # network only for n <= 16 (never in the gate's sizes); omitted for n >= 100.
    if n <= 16:
        c.append("network")
    c += ["merge", "heap", "intro", "jdk.timsort"]
    if p.has_key_stats:
        if p.counting_feasible:
            c.append("counting")
        c += ["radix.lsd", "learned"]
    return c


def oracle(a: list[int], p: Profile) -> tuple[str, dict[str, int]]:
    costs: dict[str, int] = {}
    for sid in oracle_candidates(len(a), p):
        if sid == "network":
            continue  # excluded for gate sizes; would need NET, never reached at n>=100
        comps, moves = _COST_FN[sid](a)
        costs[sid] = comps + moves
    # first-wins tie-break in candidate order == Python min over insertion-ordered dict
    best = min(costs, key=lambda s: costs[s])
    return best, costs
