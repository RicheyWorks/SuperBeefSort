"""retrain.py — Phase 4b continual-learning retrain step (ADR docs/adr-phase4-python-intelligence.md
action item 6, the retrain/hot-swap loop).

Turns the accumulated `observations.jsonl` corpus (production telemetry the SbsIntelligence server
appends in Observe — raw DataProfile + the strategy actually chosen + its measured cost) into a fresh
`sbs_selector_model.{json,txt}` in the **same flat schema** the server (`server.load_model`) and the Java
`select/SelectorModel` already read — then **atomically** swaps it into place so a running server's file
watcher / SIGHUP hot-swaps it with no restart.

Labeling (honest about what telemetry can and cannot tell us):

  * Observe gives bandit feedback — the cost of the *arm that was pulled*, never the counterfactual cost of
    the arms that weren't. A single deterministic selector pulls one arm per context, so on its own the
    corpus can't compare strategies. But the *fleet* runs a mix (cost-model / bandit / learned / explicit
    picks), so a context accumulates costs for several strategies over time. We therefore bucket
    observations into coarse contexts and label each context by its **empirically cheapest** strategy
    (mean `comparisons + moves`, the deterministic machine-independent cost the bandit also rewards on).
  * To avoid catastrophic forgetting on contexts production never exercised, we **union** the production
    rows with the original oracle-labeled seed corpus (`phase4_train.csv`). The seed keeps the model right
    where there's no production evidence; production rows refine it where there is (optionally up-weighted
    via --obs-weight). The fit is otherwise identical to `train_selector.py` (depth-8 CART), and export
    reuses `train_selector.export_tree` / `export_flat`, so the artifact is byte-compatible with both
    loaders — no format drift.

This is offline policy improvement, not online learning: it refines the static model where real traffic
gives multi-arm evidence; it does not fabricate counterfactuals. Assumptions are documented, not hidden.

Run (from tools/phase4/service):
    python3 retrain.py [observations.jsonl] [--seed ../phase4_train.csv] [--out ../sbs_selector_model]
                       [--min-support N] [--obs-weight W] [--dry-run]

`--out BASE` writes BASE.json + BASE.txt (atomic os.replace). Point it at the served model path to close
the loop directly. Exit 0 on a written model, 2 when there is nothing usable to retrain on.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import sys
import tempfile

import numpy as np
import pandas as pd
from sklearn.tree import DecisionTreeClassifier

# The mirror + trainer are the single source of truth for the feature contract and the export format.
sys.path.insert(0, "..")  # tools/phase4
import gen_corpus as gc          # FEATURE_COLUMNS (the model's declared column order)
import train_selector as ts      # export_tree / export_flat / walk_flat — reused verbatim

FEATURES = gc.FEATURE_COLUMNS
COST_METRIC = ("comparisons", "moves")  # deterministic, machine-independent (== bandit reward)

# Fit hyperparameters identical to train_selector.py, so a seed-only retrain reproduces its methodology.
TREE_KW = dict(max_depth=8, min_samples_leaf=3, random_state=0)


def features_from_obs(p: dict) -> dict:
    """Raw proto DataProfile fields (as Observe stored them) -> the 15 model features.

    Mirrors server.derive's gating (key_span/counting gated on has_key_stats — the serving contract) with
    gen_corpus.features' 6-dp rounding (the training contract), so production rows are numerically in
    lockstep with the seed corpus they're unioned with.
    """
    n = int(p["size"])
    max_inv = n * (n - 1) / 2.0 if n > 1 else 0.0
    has_ks = bool(p["has_key_stats"])
    inv = int(p["inversions"])
    dist = int(p["distinct_estimate"])
    run = int(p["longest_run"])
    return {
        "size": n,
        "sortedness_ratio": round(float(p["sortedness_ratio"]), 6),
        "has_duplicates": int(bool(p["has_duplicates"])),
        "distinct_estimate": dist,
        "distinct_ratio": round(dist / n, 6) if n else 0.0,
        "has_key_stats": int(has_ks),
        "key_span": int(p["key_span"]) if has_ks else -1,
        "counting_feasible": int(has_ks and bool(p["counting_feasible"])),
        "distribution_ord": int(p["distribution_ord"]),
        "longest_run": run,
        "longest_run_ratio": round(run / n, 6) if n else 0.0,
        "inversions": inv,
        "inversion_ratio": round(inv / max_inv, 6) if (inv >= 0 and max_inv > 0) else 0.0,
        "inversions_exact": int(bool(p["inversions_exact"])),
        "has_byte_key": int(bool(p["has_byte_key"])),
    }


def context_key(f: dict) -> tuple:
    """A coarse bucket so several observations share a context and their strategy costs are comparable.

    Categorical key facets (key stats / counting feasibility / distribution / byte key) plus an
    order-of-magnitude size bucket and a quartered sortedness band — the same kind of context the bandit
    selector buckets on, kept deliberately small so contexts fill up.
    """
    size_decade = len(str(int(f["size"])))  # digit count ~ order of magnitude
    sortedness_band = round(float(f["sortedness_ratio"]) * 4) / 4.0
    return (
        size_decade,
        int(f["has_key_stats"]),
        int(f["counting_feasible"]),
        int(f["distribution_ord"]),
        int(f["has_byte_key"]),
        sortedness_band,
    )


def read_observations(path: str) -> list[dict]:
    """Parse observations.jsonl into [{features, context, chosen, cost}] rows, skipping malformed lines."""
    rows = []
    if not os.path.exists(path):
        return rows
    with open(path) as fh:
        for ln in fh:
            ln = ln.strip()
            if not ln:
                continue
            try:
                rec = json.loads(ln)
                prof = rec["profile"]
                out = rec["outcome"]
                cost = float(out[COST_METRIC[0]]) + float(out[COST_METRIC[1]])
                if not math.isfinite(cost) or cost < 0:
                    continue
                f = features_from_obs(prof)
                rows.append({"f": f, "ctx": context_key(f), "chosen": str(rec["chosen"]), "cost": cost})
            except (KeyError, ValueError, TypeError):
                continue  # tolerate a partial/corrupt tail line without aborting the retrain
    return rows


def label_by_context(obs: list[dict], min_support: int) -> dict:
    """context -> empirically cheapest strategy (mean cost), over strategies with >= min_support samples."""
    agg: dict[tuple, dict[str, list]] = {}  # ctx -> strat -> [sum_cost, count]
    for o in obs:
        per = agg.setdefault(o["ctx"], {})
        acc = per.setdefault(o["chosen"], [0.0, 0])
        acc[0] += o["cost"]
        acc[1] += 1
    best: dict[tuple, str] = {}
    for ctx, per in agg.items():
        means = {s: c[0] / c[1] for s, c in per.items() if c[1] >= min_support}
        if not means:  # nothing cleared support; fall back to any-support so the row still labels
            means = {s: c[0] / c[1] for s, c in per.items()}
        best[ctx] = min(means, key=means.get)
    return best


def build_training_frame(obs: list[dict], best: dict, seed_csv: str, obs_weight: float):
    """Production rows (features, empirical-best label) unioned with the oracle seed corpus.

    Returns (X, y, sample_weight) with seed weight 1.0 and observation weight obs_weight.
    """
    Xo = [[o["f"][c] for c in FEATURES] for o in obs]
    yo = [best[o["ctx"]] for o in obs]
    wo = [float(obs_weight)] * len(obs)

    seed = pd.read_csv(seed_csv)
    Xs = seed[FEATURES].to_numpy(float).tolist()
    ys = seed["oracle"].astype(str).tolist()
    ws = [1.0] * len(ys)

    X = np.asarray(Xo + Xs, dtype=float) if (Xo or Xs) else np.empty((0, len(FEATURES)))
    y = np.asarray(yo + ys)
    w = np.asarray(wo + ws, dtype=float)
    return X, y, w, len(obs), len(ys)


def atomic_write(path: str, write_fn) -> None:
    """write_fn(tmp_path) produces the file; we then os.replace it onto `path` (atomic on POSIX/NTFS)."""
    d = os.path.dirname(os.path.abspath(path)) or "."
    fd, tmp = tempfile.mkstemp(dir=d, prefix=".retrain-", suffix=os.path.splitext(path)[1])
    os.close(fd)
    try:
        write_fn(tmp)
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)


def retrain(observations: str, seed_csv: str, out_base: str, min_support: int,
            obs_weight: float, dry_run: bool) -> int:
    obs = read_observations(observations)
    if not obs:
        print(f"retrain: no usable observations in {observations} — nothing to retrain on (keeping current model)")
        return 2

    best = label_by_context(obs, min_support)
    X, y, w, n_obs, n_seed = build_training_frame(obs, best, seed_csv, obs_weight)

    clf = DecisionTreeClassifier(**TREE_KW)
    clf.fit(X, y, sample_weight=w)
    classes = [str(c) for c in clf.classes_]  # plain str, not numpy.str_, for clean logs + export

    # Informational: how many contexts the production evidence relabeled away from the chosen arm.
    chosen_by_ctx: dict = {}
    for o in obs:
        chosen_by_ctx.setdefault(o["ctx"], set()).add(o["chosen"])
    flips = sum(1 for ctx, lab in best.items()
                if any(c != lab for c in chosen_by_ctx.get(ctx, ())))
    print(f"retrain: {n_obs} obs rows over {len(best)} contexts (obs_weight={obs_weight}) "
          f"+ {n_seed} seed rows -> tree depth {clf.get_depth()}, {clf.tree_.node_count} nodes, "
          f"classes={classes}")
    print(f"         contexts whose empirical-best differs from the chosen arm: {flips}")

    # Reuse the trainer's exact exporters so the artifact is byte-compatible with both loaders.
    json_path, txt_path = out_base + ".json", out_base + ".txt"
    if dry_run:
        print(f"retrain: --dry-run, not writing {json_path} / {txt_path}")
        return 0

    model = None

    def write_json(tmp):
        nonlocal model
        model = ts.export_tree(clf, classes, tmp)

    atomic_write(json_path, write_json)
    atomic_write(txt_path, lambda tmp: ts.export_flat(model, tmp))

    # Round-trip self-check: the flat walker must reproduce sklearn on the training X (the serving contract).
    mism = 0
    for i in range(len(X)):
        xrow = {c: X[i][j] for j, c in enumerate(FEATURES)}
        if ts.walk_flat(txt_path, xrow) != clf.predict(X[i:i + 1])[0]:
            mism += 1
    print(f"retrain: wrote {txt_path} (+ .json); flat round-trip {len(X) - mism}/{len(X)} "
          f"({'OK' if mism == 0 else 'MISMATCH'})")
    return 0 if mism == 0 else 1


def main() -> int:
    ap = argparse.ArgumentParser(description="Retrain the SBS learned selector from the observe corpus.")
    ap.add_argument("observations", nargs="?", default="observations.jsonl")
    ap.add_argument("--seed", default="../phase4_train.csv", help="oracle-labeled seed corpus (anti-forgetting)")
    ap.add_argument("--out", default="../sbs_selector_model", help="output base path (writes BASE.json + BASE.txt)")
    ap.add_argument("--min-support", type=int, default=1, help="min samples for a strategy to label a context")
    ap.add_argument("--obs-weight", type=float, default=1.0, help="sample weight on production rows vs seed=1.0")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    return retrain(a.observations, a.seed, a.out, a.min_support, a.obs_weight, a.dry_run)


if __name__ == "__main__":
    sys.exit(main())
