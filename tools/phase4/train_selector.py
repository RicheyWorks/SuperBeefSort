"""
train_selector.py — Phase 4a: train a compact strategy classifier over DataProfile
features and export it in a stable, versioned format the Java
LearnedModelStrategySelector loads and evaluates in-process.

Pipeline:
  1. Load phase4_train.csv (fit) and phase4_gate_test.csv (held-out benchmark).
  2. Features = the DataProfile columns a selector can compute at runtime.
     Label   = the brute-force oracle's cheapest strategy.
  3. Fit a DecisionTreeClassifier (primary — trivially exportable as thresholds);
     also report GradientBoosting + multinomial-logistic accuracy as headroom.
  4. Score on the gate test set: exact-match vs the oracle + mean/max regret
     (from the per-strategy cost columns), with predictions masked to the
     strategies that are actually feasible for each workload. Compare to the
     gate's published bandit (65.4% exact / 191.94% mean regret) and cost model
     (60.5% / 386.52%).
  5. Export sbs_selector_model.json (schema v1): feature order, class names,
     and the decision-tree arrays (feature/threshold/left/right/class/confidence).

Run:  python3 train_selector.py
"""

from __future__ import annotations

import json
import sys

import numpy as np
import pandas as pd
from sklearn.tree import DecisionTreeClassifier
from sklearn.ensemble import GradientBoostingClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import cross_val_score
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

import gen_corpus as gc

MODEL_SCHEMA_VERSION = 1
FEATURES = gc.FEATURE_COLUMNS
STRATEGIES = gc.STRATEGIES
COST_COLS = gc.COST_COLUMNS

# Published gate baselines on the same 324 workloads (ADR action item 2).
GATE_BASELINES = {
    "cost-model": {"exact": 60.5, "mean_regret": 386.52},
    "bandit": {"exact": 65.4, "mean_regret": 191.94},
}


def load():
    train_path = sys.argv[1] if len(sys.argv) > 1 else "phase4_train.csv"
    test_path = sys.argv[2] if len(sys.argv) > 2 else "phase4_gate_test.csv"
    print(f"train_csv={train_path}  test_csv={test_path}")
    return pd.read_csv(train_path), pd.read_csv(test_path)


def feasible_mask(row) -> dict:
    """Per-workload cost of each candidate strategy (NaN => not a candidate)."""
    return {s: row[f"cost_{s}"] for s in STRATEGIES}


def masked_predict(clf, X, rows, classes):
    """Predict the highest-probability strategy that is FEASIBLE for each row
    (has a finite cost). Mirrors the real selector never returning an
    inapplicable strategy."""
    proba = clf.predict_proba(X)
    preds = []
    for i, (_, row) in enumerate(rows.iterrows()):
        costs = feasible_mask(row)
        order = np.argsort(-proba[i])  # classes by descending probability
        choice = None
        for ci in order:
            s = classes[ci]
            if np.isfinite(costs.get(s, np.nan)):
                choice = s
                break
        if choice is None:  # nothing known is feasible; fall back to oracle-safe intro
            choice = "intro"
        preds.append(choice)
    return preds


def regret_report(name, preds, test):
    exact = 0
    regrets = []
    for pred, (_, row) in zip(preds, test.iterrows()):
        oracle = row["oracle"]
        if pred == oracle:
            exact += 1
        oc = float(row[f"cost_{oracle}"])
        pc = float(row[f"cost_{pred}"])
        regrets.append(0.0 if oc == 0 else (pc - oc) / oc)
    n = len(preds)
    regrets = np.array(regrets)
    near = int((regrets < 0.05).sum())
    out = {
        "exact": 100.0 * exact / n,
        "near_opt": 100.0 * near / n,
        "mean_regret": 100.0 * regrets.mean(),
        "max_regret": 100.0 * regrets.max(),
    }
    print(f"  {name:<16} exact={out['exact']:5.1f}%  near-opt(<5%)={out['near_opt']:5.1f}%  "
          f"mean_regret={out['mean_regret']:7.2f}%  max_regret={out['max_regret']:7.1f}%")
    return out


def export_tree(clf: DecisionTreeClassifier, classes, path):
    t = clf.tree_
    n_nodes = t.node_count
    feature = []
    threshold = []
    left = []
    right = []
    cls = []
    confidence = []
    for i in range(n_nodes):
        is_leaf = t.children_left[i] == t.children_right[i]  # both -1
        feature.append(-1 if is_leaf else int(t.feature[i]))
        threshold.append(0.0 if is_leaf else float(t.threshold[i]))
        left.append(int(t.children_left[i]))
        right.append(int(t.children_right[i]))
        counts = t.value[i][0]
        cls.append(int(np.argmax(counts)))
        confidence.append(float(counts.max() / counts.sum()))
    model = {
        "schema_version": MODEL_SCHEMA_VERSION,
        "model_type": "decision_tree",
        "trained_on": "sbs-mirror corpus (java.util.Random-faithful); "
                      "host Phase4CorpusDump emits the same schema",
        "feature_columns": FEATURES,
        "classes": list(classes),
        "n_nodes": n_nodes,
        "nodes": {
            "feature": feature,        # index into feature_columns; -1 at a leaf
            "threshold": threshold,    # go LEFT when feature <= threshold
            "left": left,              # child node ids; -1 at a leaf
            "right": right,
            "class": cls,              # argmax class index (valid at leaves)
            "confidence": confidence,  # leaf purity, for the selector's confidence gate
        },
    }
    with open(path, "w") as f:
        json.dump(model, f, indent=2)
    print(f"exported {path}  (schema v{MODEL_SCHEMA_VERSION}, {n_nodes} nodes, depth {clf.get_depth()})")
    return model



def export_flat(model, path):
    """Emit the same tree as a dependency-free, line-oriented file the Java
    SelectorModel loads with String.split (no JSON parser on the classpath)."""
    nd = model["nodes"]
    lines = [
        f"sbs-selector-model {model['schema_version']} {model['model_type']}",
        "features " + ",".join(model["feature_columns"]),
        "classes " + ",".join(model["classes"]),
        f"n_nodes {model['n_nodes']}",
        "feature " + " ".join(str(v) for v in nd["feature"]),
        "threshold " + " ".join(f"{v:.6f}" for v in nd["threshold"]),
        "left " + " ".join(str(v) for v in nd["left"]),
        "right " + " ".join(str(v) for v in nd["right"]),
        "class " + " ".join(str(v) for v in nd["class"]),
        "confidence " + " ".join(f"{v:.6f}" for v in nd["confidence"]),
    ]
    with open(path, "w") as f:
        f.write("\n".join(lines) + "\n")
    print(f"exported {path}  (flat, {model['n_nodes']} nodes)")


def walk_flat(path, x_row):
    """Reference walker for the flat format (the exact logic the Java loader uses)."""
    kv = {}
    for line in open(path):
        line = line.rstrip("\n")
        if line.startswith("sbs-selector-model"):
            continue
        key, _, rest = line.partition(" ")
        kv[key] = rest
    feats = kv["features"].split(",")
    classes = kv["classes"].split(",")
    feature = [int(v) for v in kv["feature"].split()]
    threshold = [float(v) for v in kv["threshold"].split()]
    left = [int(v) for v in kv["left"].split()]
    right = [int(v) for v in kv["right"].split()]
    cls = [int(v) for v in kv["class"].split()]
    node = 0
    while feature[node] >= 0:
        node = left[node] if x_row[feats[feature[node]]] <= threshold[node] else right[node]
    return classes[cls[node]]


def walk_tree(model, x_row):
    """Reference walker (the exact logic the Java loader implements) — used to
    verify the exported JSON round-trips the sklearn predictions."""
    nd = model["nodes"]
    feats = model["feature_columns"]
    node = 0
    while nd["feature"][node] >= 0:
        f = nd["feature"][node]
        node = nd["left"][node] if x_row[feats[f]] <= nd["threshold"][node] else nd["right"][node]
    return model["classes"][nd["class"][node]]


def main() -> int:
    train, test = load()
    Xtr, ytr = train[FEATURES].to_numpy(float), train["oracle"].to_numpy()
    Xte = test[FEATURES].to_numpy(float)

    print(f"train={len(train)} rows  test(gate)={len(test)} rows  "
          f"features={len(FEATURES)}  classes={sorted(set(ytr))}")

    # --- model comparison (5-fold CV on train) ---------------------------- #
    print("\n5-fold CV accuracy on train:")
    models = {
        "decision_tree(d=8)": DecisionTreeClassifier(max_depth=8, min_samples_leaf=3,
                                                     random_state=0),
        "grad_boost": GradientBoostingClassifier(random_state=0),
        "logistic(multinomial)": make_pipeline(
            StandardScaler(),
            LogisticRegression(max_iter=2000, C=1.0)),
    }
    for name, mdl in models.items():
        sc = cross_val_score(mdl, Xtr, ytr, cv=5)
        print(f"  {name:<24} {sc.mean()*100:5.1f}% ± {sc.std()*100:4.1f}%")

    # --- fit the exportable tree on all train ----------------------------- #
    clf = DecisionTreeClassifier(max_depth=8, min_samples_leaf=3, random_state=0)
    clf.fit(Xtr, ytr)
    classes = list(clf.classes_)

    # --- score on the held-out gate benchmark ----------------------------- #
    print("\nGate benchmark (held-out 324 workloads):")
    print("  -- gate's published baselines --")
    for nm, b in GATE_BASELINES.items():
        print(f"  {nm:<16} exact={b['exact']:5.1f}%  "
              f"{'':<18}mean_regret={b['mean_regret']:7.2f}%")
    print("  -- learned model --")
    preds = masked_predict(clf, Xte, test, classes)
    learned = regret_report("learned_tree", preds, test)

    # feature importances (interpretability)
    imp = sorted(zip(FEATURES, clf.feature_importances_), key=lambda kv: -kv[1])
    print("\n  top feature importances:")
    for f, v in imp[:6]:
        if v > 0:
            print(f"    {f:<20} {v:.3f}")

    # --- export + round-trip verification --------------------------------- #
    print()
    model = export_tree(clf, classes, "sbs_selector_model.json")
    export_flat(model, "sbs_selector_model.txt")
    # verify the JSON walker reproduces sklearn's predictions exactly
    reloaded = json.load(open("sbs_selector_model.json"))
    mism = 0
    for i in range(len(test)):
        x = {f: Xte[i][j] for j, f in enumerate(FEATURES)}
        if walk_tree(reloaded, x) != clf.predict(Xte[i:i+1])[0]:
            mism += 1
    print(f"export round-trip: {len(test)-mism}/{len(test)} predictions match sklearn "
          f"({'OK' if mism == 0 else 'MISMATCH'})")
    fmis = sum(1 for i in range(len(test))
               if walk_flat("sbs_selector_model.txt",
                            {f: Xte[i][j] for j, f in enumerate(FEATURES)})
               != clf.predict(Xte[i:i+1])[0])
    print(f"flat round-trip:   {len(test)-fmis}/{len(test)} predictions match sklearn "
          f"({'OK' if fmis == 0 else 'MISMATCH'})")

    # --- verdict ----------------------------------------------------------- #
    print("\nVerdict:")
    b = GATE_BASELINES["bandit"]
    dexact = learned["exact"] - b["exact"]
    print(f"  learned exact {learned['exact']:.1f}% vs bandit {b['exact']:.1f}%  "
          f"=> {dexact:+.1f} pts")
    print(f"  learned mean_regret {learned['mean_regret']:.2f}% vs bandit "
          f"{b['mean_regret']:.2f}%")
    beats = learned["exact"] > b["exact"] and learned["mean_regret"] < b["mean_regret"]
    print("  RESULT:", "learned model beats the bandit on the gate benchmark"
          if beats else "no clear win — revisit features/model")
    return 0


if __name__ == "__main__":
    sys.exit(main())
