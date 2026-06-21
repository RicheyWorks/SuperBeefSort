"""
gen_corpus.py — generate labeled training/eval corpora from the validated mirror.

Emits two CSVs with an identical schema:
  - phase4_train.csv : a large grid DISJOINT from the gate (richer sizes/trials,
                       a distinct seed salt), for fitting the model.
  - phase4_gate_test.csv : the exact 324 Phase4DecisionGate workloads, the
                       held-out benchmark on which the gate measured the bandit
                       (65.4% exact / 191.94% mean regret) and cost model
                       (60.5% / 386.52%). The learned selector is scored here.

Columns: metadata (shape,n,with_keys,seed) + model features (the DataProfile
fields a LearnedModelStrategySelector can compute at selection time) + the oracle
label + per-strategy cost (comparisons+moves), blank when not a candidate.

The same schema is emitted by the Java Phase4CorpusDump (host-run, real profiler
features) so train_selector.py consumes either source unchanged.

Usage:
  python3 gen_corpus.py [gate|train|all]     (default: all)
"""

from __future__ import annotations

import csv
import sys
import time

import sbs_mirror as m

FEATURE_COLUMNS = [
    "size",
    "sortedness_ratio",
    "has_duplicates",
    "distinct_estimate",
    "distinct_ratio",
    "has_key_stats",
    "key_span",
    "counting_feasible",
    "distribution_ord",
    "longest_run",
    "longest_run_ratio",
    "inversions",
    "inversion_ratio",
    "inversions_exact",
    "has_byte_key",
]
META_COLUMNS = ["shape", "n", "with_keys", "seed"]
STRATEGIES = ["insertion", "merge", "heap", "intro", "jdk.timsort",
              "counting", "radix.lsd", "learned"]
COST_COLUMNS = [f"cost_{s}" for s in STRATEGIES]
ALL_COLUMNS = META_COLUMNS + FEATURE_COLUMNS + ["oracle"] + COST_COLUMNS

_DIST_ORD = {"UNKNOWN": 0, "UNIFORM": 1, "SKEWED": 2, "CLUSTERED": 3}


def features(p: m.Profile) -> dict:
    n = p.size
    max_inv = n * (n - 1) / 2.0 if n > 1 else 0.0
    return {
        "size": n,
        "sortedness_ratio": round(p.sortedness_ratio, 6),
        "has_duplicates": int(p.has_duplicates),
        "distinct_estimate": p.distinct_estimate,
        "distinct_ratio": round(p.distinct_estimate / n, 6) if n else 0.0,
        "has_key_stats": int(p.has_key_stats),
        "key_span": p.key_span if p.key_span is not None else -1,
        "counting_feasible": int(bool(p.counting_feasible)),
        "distribution_ord": _DIST_ORD[p.distribution],
        "longest_run": p.longest_run,
        "longest_run_ratio": round(p.longest_run / n, 6) if n else 0.0,
        "inversions": p.inversions,
        "inversion_ratio": round(p.inversions / max_inv, 6) if max_inv > 0 else 0.0,
        "inversions_exact": int(p.inversions_exact),
        "has_byte_key": int(p.has_byte_key),
    }


def row_for(shape: str, n: int, with_keys: bool, seed: int) -> dict:
    data = m.generate(shape, n, seed)
    prof = m.profile(data, with_keys)
    best, costs = m.oracle(data, prof)
    row = {"shape": shape, "n": n, "with_keys": int(with_keys), "seed": seed}
    row.update(features(prof))
    row["oracle"] = best
    for s in STRATEGIES:
        row[f"cost_{s}"] = costs.get(s, "")
    return row


def write_csv(path: str, rows: list) -> None:
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=ALL_COLUMNS)
        w.writeheader()
        w.writerows(rows)
    print(f"wrote {len(rows):4d} rows -> {path}")


GATE_SIZES = [100, 500, 1000, 5000, 10000, 50000]
GATE_TRIALS = 3

TRAIN_GRID = [
    (64, 6), (128, 6), (256, 6), (400, 6), (750, 6),
    (1500, 6), (3000, 6), (6000, 6), (12000, 3), (20000, 3),
]
TRAIN_SIZES = [n for n, _ in TRAIN_GRID]
TRAIN_SALT = 7_000_003


def gate_rows() -> list:
    rows = []
    for n in GATE_SIZES:
        for shape in m.SHAPES:
            for wk in (True, False):
                for t in range(GATE_TRIALS):
                    rows.append(row_for(shape, n, wk, m.gate_seed(shape, n, t, wk)))
    return rows


def train_rows() -> list:
    rows = []
    for n, trials in TRAIN_GRID:
        for shape in m.SHAPES:
            for wk in (True, False):
                for t in range(trials):
                    seed = m.gate_seed(shape, n, t, wk) + TRAIN_SALT
                    rows.append(row_for(shape, n, wk, seed))
    return rows


def main() -> int:
    what = sys.argv[1] if len(sys.argv) > 1 else "all"
    assert not (set(GATE_SIZES) & set(TRAIN_SIZES)), "train/test size overlap"
    if what in ("gate", "all"):
        t0 = time.time()
        write_csv("phase4_gate_test.csv", gate_rows())
        print(f"  gate test built in {time.time() - t0:.1f}s")
    if what in ("train", "all"):
        t1 = time.time()
        write_csv("phase4_train.csv", train_rows())
        print(f"  train built in {time.time() - t1:.1f}s")
    print("train/test disjoint by size — OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
