"""SbsIntelligence gRPC server (Phase 4b, ADR docs/adr-phase4-python-intelligence.md action item 6).

Serves the offline-trained flat decision tree (tools/phase4/sbs_selector_model.txt, schema v1) and ingests
the engine's observe() stream for continual retrain. Pure stdlib + grpc:

  - the tree walk mirrors Java select/SelectorModel.predict (same flat arrays);
  - feature derivation mirrors tools/phase4 gen_corpus.features / Java
    LearnedModelStrategySelector.featureValue (UNROUNDED), so training and serving stay in feature lockstep.

The client (Java RemoteStrategySelector) sends the RAW DataProfile fields; the server derives the model
feature vector here so the derivation lives in exactly one place. Predict advises only under SMART policy;
any other policy returns an empty strategy_id (= "no advice", client keeps its local delegate).

Generate stubs first:  ./gen.sh    (writes sbs_intelligence_pb2*.py here)
Run:                    python3 server.py [model_path] [corpus_path] [port]
Defaults:               ../sbs_selector_model.txt   observations.jsonl   50051

Verified in-sandbox by smoke_test.py: 72/72 Predict parity vs an independent unrounded oracle across
4 sizes x 9 shapes x 2 key modes; Observe + non-SMART paths covered.

Continual learning (follow-up): retrain from the accumulated observations.jsonl with train_selector.py and
hot-swap the served model; the model_version field surfaces which model answered.
"""
from __future__ import annotations

import hashlib
import json
import os
import signal
import sys
import threading
import time
from concurrent import futures

import grpc

import sbs_intelligence_pb2 as pb
import sbs_intelligence_pb2_grpc as rpc


def _structure_hash(m: dict) -> str:
    """Short content hash over the tree's structural arrays — formatting-independent, so model_version
    changes exactly when the served decision tree changes. This is what makes a hot-swap visible to
    callers (PredictResponse.model_version) even if a retrain happens to land the same node count."""
    canon = "|".join((
        ",".join(m["classes"]),
        ",".join(m["features"]),
        " ".join(str(v) for v in m["feature"]),
        " ".join(f"{v:.6f}" for v in m["threshold"]),
        " ".join(str(v) for v in m["left"]),
        " ".join(str(v) for v in m["right"]),
        " ".join(str(v) for v in m["class"]),
        " ".join(f"{v:.6f}" for v in m["confidence"]),
    ))
    return hashlib.sha1(canon.encode()).hexdigest()[:8]


def load_model(path: str) -> dict:
    """Parse the schema-v1 flat decision tree (line-oriented twin of the JSON export)."""
    kv = {}
    version = model_type = None
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            if line.startswith("sbs-selector-model"):
                h = line.split()
                version, model_type = int(h[1]), h[2]
                continue
            k, _, v = line.partition(" ")
            kv[k] = v.strip()
    if version != 1:
        raise ValueError(f"unsupported model schema {version}")
    m = {
        "features": [c.strip() for c in kv["features"].split(",") if c.strip()],
        "classes": [c.strip() for c in kv["classes"].split(",") if c.strip()],
        "feature": [int(x) for x in kv["feature"].split()],
        "threshold": [float(x) for x in kv["threshold"].split()],
        "left": [int(x) for x in kv["left"].split()],
        "right": [int(x) for x in kv["right"].split()],
        "class": [int(x) for x in kv["class"].split()],
        "confidence": [float(x) for x in kv["confidence"].split()],
    }
    m["version"] = f"{model_type}.s{version}.n{len(m['feature'])}.{_structure_hash(m)}"
    return m


def derive(p) -> dict:
    """proto DataProfile -> the 15 model features. Byte-for-byte the Java featureValue / gen_corpus.features."""
    n = p.size
    max_inv = n * (n - 1) / 2.0 if n > 1 else 0.0
    return {
        "size": float(n),
        "sortedness_ratio": p.sortedness_ratio,
        "has_duplicates": 1.0 if p.has_duplicates else 0.0,
        "distinct_estimate": float(p.distinct_estimate),
        "distinct_ratio": (p.distinct_estimate / n) if n else 0.0,
        "has_key_stats": 1.0 if p.has_key_stats else 0.0,
        "key_span": float(p.key_span) if p.has_key_stats else -1.0,
        "counting_feasible": 1.0 if (p.has_key_stats and p.counting_feasible) else 0.0,
        "distribution_ord": float(p.distribution_ord),
        "longest_run": float(p.longest_run),
        "longest_run_ratio": (p.longest_run / n) if n else 0.0,
        "inversions": float(p.inversions),
        "inversion_ratio": (p.inversions / max_inv) if (p.inversions >= 0 and max_inv > 0) else 0.0,
        "inversions_exact": 1.0 if p.inversions_exact else 0.0,
        "has_byte_key": 1.0 if p.has_byte_key else 0.0,
    }


def walk(model: dict, feats: dict):
    """Flat decision-tree walk; identical to Java SelectorModel.predict."""
    x = [feats[c] for c in model["features"]]
    node = 0
    while model["feature"][node] >= 0:
        fi = model["feature"][node]
        node = model["left"][node] if x[fi] <= model["threshold"][node] else model["right"][node]
    return model["classes"][model["class"][node]], model["confidence"][node]


class ModelStore:
    """Holds the active model and supports atomic hot-swap from disk — no server restart.

    current() is a single attribute read (atomic in CPython), so a Predict in flight never sees a torn
    model: it either gets the whole old model or the whole new one. reload() re-parses the file under a
    lock and swaps the reference; maybe_reload() does so only when the file's (mtime, size) changed since
    the last load — the cheap check the poll loop runs. Pairs with retrain.py's atomic os.replace, so the
    watcher only ever observes a complete old or complete new file, never a half-written one."""

    def __init__(self, path: str):
        self._path = path
        self._lock = threading.Lock()
        self._model = load_model(path)
        self._sig = self._file_sig()

    def _file_sig(self):
        st = os.stat(self._path)
        return (st.st_mtime_ns, st.st_size)

    def current(self) -> dict:
        return self._model

    def reload(self) -> str:
        with self._lock:
            self._model = load_model(self._path)
            try:
                self._sig = self._file_sig()
            except OSError:
                pass
            return self._model["version"]

    def maybe_reload(self):
        """Reload iff the file changed on disk since the last load. Returns the new version, or None."""
        try:
            sig = self._file_sig()
        except OSError:
            return None
        if sig != self._sig:
            return self.reload()
        return None

    @property
    def path(self) -> str:
        return self._path


def _start_watcher(store: ModelStore, poll_secs: float):
    """Daemon thread: poll the model file and hot-swap on change. Returns the thread (for tests)."""
    def loop():
        while True:
            time.sleep(poll_secs)
            try:
                v = store.maybe_reload()
            except Exception as e:  # tolerate a transient read error; try again next tick
                print(f"[hot-swap] reload skipped: {e}")
                continue
            if v:
                print(f"[hot-swap] model file changed -> reloaded {v}")
    t = threading.Thread(target=loop, name="sbs-model-watcher", daemon=True)
    t.start()
    return t


class Servicer(rpc.SbsIntelligenceServicer):
    def __init__(self, store: ModelStore, corpus_path: str):
        self.store = store
        self.corpus_path = corpus_path
        self._lock = threading.Lock()
        self._n = 0

    def Predict(self, request, context):
        model = self.store.current()  # snapshot once; a concurrent hot-swap cannot tear this call
        # The model is a SMART-objective advisor; other policies keep their own guarantees (no advice).
        if request.policy and request.policy != "SMART":
            return pb.PredictResponse(strategy_id="", confidence=0.0, model_version=model["version"])
        label, conf = walk(model, derive(request.profile))
        return pb.PredictResponse(strategy_id=label, confidence=conf, model_version=model["version"])

    def Observe(self, request, context):
        p = request.profile
        row = {
            "profile": {fld.name: getattr(p, fld.name) for fld in p.DESCRIPTOR.fields},
            "chosen": request.chosen_strategy,
            "outcome": {
                "comparisons": request.outcome.comparisons,
                "moves": request.outcome.moves,
                "elapsed_nanos": request.outcome.elapsed_nanos,
                "peak_aux_bytes": request.outcome.peak_aux_bytes,
            },
        }
        with self._lock:
            with open(self.corpus_path, "a") as f:
                f.write(json.dumps(row) + "\n")
            self._n += 1
            n = self._n
        return pb.ObserveResponse(accepted=True, corpus_size=n)


def serve(model_path: str, corpus_path: str, port: int = 0, watch: bool = False,
          poll_secs: float = 1.0, sighup: bool = False):
    """Start the server; returns (server, bound_port). port=0 binds an ephemeral port (used by the smoke test).

    watch=True starts a file watcher that hot-swaps the model whenever model_path changes on disk;
    sighup=True additionally reloads on SIGHUP (POSIX). Both default OFF, so the smoke test's serve(...) is
    byte-for-byte unchanged. The ModelStore is the swap point either way."""
    store = ModelStore(model_path)
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=4))
    rpc.add_SbsIntelligenceServicer_to_server(Servicer(store, corpus_path), server)
    bound = server.add_insecure_port(f"127.0.0.1:{port}")
    server.start()
    if watch:
        _start_watcher(store, poll_secs)
    if sighup and hasattr(signal, "SIGHUP"):
        signal.signal(signal.SIGHUP, lambda *_: print(f"[hot-swap] SIGHUP -> reloaded {store.reload()}"))
    return server, bound


if __name__ == "__main__":
    model_path = sys.argv[1] if len(sys.argv) > 1 else "../sbs_selector_model.txt"
    corpus_path = sys.argv[2] if len(sys.argv) > 2 else "observations.jsonl"
    port = int(sys.argv[3]) if len(sys.argv) > 3 else 50051
    s, b = serve(model_path, corpus_path, port, watch=True, sighup=True)
    print(f"SbsIntelligence serving on 127.0.0.1:{b} (model {model_path}; hot-swap: file-watch + SIGHUP)")
    s.wait_for_termination()
