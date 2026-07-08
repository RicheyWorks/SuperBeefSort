"""End-to-end test for the Phase 4b retrain + hot-swap loop (retrain.py + server.ModelStore / watcher).

Runs fully in-sandbox (sklearn + grpc via pip; stubs generated on demand):

  1. retrain unit — synth observations.jsonl with a context where one strategy is overwhelmingly cheapest;
     retrain; assert a valid, version-bumped flat model whose flat-walk reproduces sklearn, and that the
     planted feature vector's label FLIPS from the base model's pick to the planted strategy.
  2. ModelStore hot-swap unit (no gRPC) — load model A -> version vA; replace the file with B on disk;
     reload() -> vB != vA; current() then walks B.
  3. end-to-end gRPC hot-swap — serve(watch=True) over model A; Predict -> vA; atomically replace the served
     file with B (exactly what retrain.py does); wait for the watcher; Predict -> vB, no server restart.

Run from tools/phase4/service:   python3 retrain_test.py
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
import time

BASE_MODEL = "../sbs_selector_model.txt"
SEED_CSV = "../phase4_train.csv"
PROTO_DIR = "../../../src/main/proto"


def ensure_stubs() -> None:
    """Generate the Python gRPC stubs from the canonical proto if they're not already present."""
    if os.path.exists("sbs_intelligence_pb2.py") and os.path.exists("sbs_intelligence_pb2_grpc.py"):
        return
    subprocess.run([sys.executable, "-m", "grpc_tools.protoc", "-I", PROTO_DIR,
                    "--python_out=.", "--grpc_python_out=.", f"{PROTO_DIR}/sbs_intelligence.proto"],
                   check=True)


ensure_stubs()
import grpc  # noqa: E402
import sbs_intelligence_pb2 as pb  # noqa: E402
import sbs_intelligence_pb2_grpc as rpc  # noqa: E402
import server as srv  # noqa: E402
import retrain as rt  # noqa: E402

# A large, counting-feasible, clustered keyed integer input — the kind of context the model routes on.
PLANTED_PROFILE = {
    "size": 5000, "sortedness_ratio": 0.5, "has_duplicates": True,
    "distinct_estimate": 2000, "has_key_stats": True, "key_span": 4000,
    "counting_feasible": True, "distribution_ord": 3, "longest_run": 30,
    "inversions": 6_000_000, "inversions_exact": False, "has_byte_key": False,
}


def to_proto(p: dict) -> "pb.DataProfile":
    return pb.DataProfile(
        size=p["size"], sortedness_ratio=p["sortedness_ratio"], has_duplicates=bool(p["has_duplicates"]),
        distinct_estimate=p["distinct_estimate"], has_key_stats=bool(p["has_key_stats"]),
        key_span=int(p["key_span"]), counting_feasible=bool(p["counting_feasible"]),
        distribution_ord=p["distribution_ord"], longest_run=p["longest_run"],
        inversions=p["inversions"], inversions_exact=bool(p["inversions_exact"]),
        has_byte_key=bool(p["has_byte_key"]))


def write_observations(path: str, planted: str, base_label: str, n: int = 500) -> None:
    """planted strategy at trivial cost (the empirical winner) + the base pick at huge cost (the loser)."""
    cheap = {"comparisons": 1, "moves": 1, "elapsed_nanos": 10, "peak_aux_bytes": 0}
    dear = {"comparisons": 10**9, "moves": 10**9, "elapsed_nanos": 10**9, "peak_aux_bytes": 0}
    import json
    with open(path, "w") as f:
        for _ in range(n):
            f.write(json.dumps({"profile": PLANTED_PROFILE, "chosen": planted, "outcome": cheap}) + "\n")
        for _ in range(n // 5):
            f.write(json.dumps({"profile": PLANTED_PROFILE, "chosen": base_label, "outcome": dear}) + "\n")


def test_retrain_flips_label(workdir: str) -> tuple[str, str]:
    """Returns (base_model_path, retrained_model_base) for the downstream hot-swap tests."""
    feat = rt.features_from_obs(PLANTED_PROFILE)
    base = srv.load_model(BASE_MODEL)
    base_label, _ = srv.walk(base, feat)

    # Pick a planted strategy that is in the base class set but is NOT what the base model predicts here.
    planted = next(c for c in base["classes"] if c != base_label)

    obs = os.path.join(workdir, "observations.jsonl")
    write_observations(obs, planted, base_label)

    out_base = os.path.join(workdir, "retrained_model")
    rc = rt.retrain(obs, SEED_CSV, out_base, min_support=1, obs_weight=500.0, dry_run=False)
    assert rc == 0, f"retrain returned {rc}"

    retrained = srv.load_model(out_base + ".txt")
    new_label, _ = srv.walk(retrained, feat)

    assert base_label != planted, "test setup: planted must differ from the base pick"
    assert new_label == planted, f"expected flip to {planted!r}, got {new_label!r} (was {base_label!r})"
    assert retrained["version"] != base["version"], "model_version must change after a retrain"
    print(f"  [1] retrain flip: base={base_label!r} -> retrained={new_label!r}  "
          f"(version {base['version']} -> {retrained['version']})  OK")
    return BASE_MODEL, out_base


def test_modelstore_hotswap(workdir: str, retrained_base: str) -> None:
    served = os.path.join(workdir, "served_unit.txt")
    shutil.copyfile(BASE_MODEL, served)
    store = srv.ModelStore(served)
    vA = store.current()["version"]

    # Replace the file on disk with the retrained model, then reload.
    shutil.copyfile(retrained_base + ".txt", served)
    vB = store.reload()
    assert vB != vA, f"reload should change version: {vA} -> {vB}"
    assert store.current()["version"] == vB
    # maybe_reload is now a no-op (file unchanged since the reload).
    assert store.maybe_reload() is None
    print(f"  [2] ModelStore reload: {vA} -> {vB}  (maybe_reload no-ops when unchanged)  OK")


def test_grpc_hotswap(workdir: str, retrained_base: str) -> None:
    served = os.path.join(workdir, "served_grpc.txt")
    shutil.copyfile(BASE_MODEL, served)
    corpus = os.path.join(workdir, "obs_e2e.jsonl")
    open(corpus, "w").close()

    server, port = srv.serve(served, corpus, 0, watch=True, poll_secs=0.25)
    try:
        stub = rpc.SbsIntelligenceStub(grpc.insecure_channel(f"127.0.0.1:{port}"))
        req = pb.PredictRequest(profile=to_proto(PLANTED_PROFILE), policy="SMART")
        rA = stub.Predict(req)
        vA = rA.model_version

        # Atomically swap the served model exactly as retrain.py does (write temp + os.replace).
        tmp = served + ".tmp"
        shutil.copyfile(retrained_base + ".txt", tmp)
        os.replace(tmp, served)

        deadline = time.time() + 5.0
        rB = stub.Predict(req)
        while rB.model_version == vA and time.time() < deadline:
            time.sleep(0.1)
            rB = stub.Predict(req)

        assert rB.model_version != vA, f"watcher did not hot-swap within timeout (still {vA})"
        print(f"  [3] gRPC hot-swap: Predict version {vA} -> {rB.model_version} "
              f"(strategy {rA.strategy_id!r} -> {rB.strategy_id!r}), no restart  OK")
    finally:
        server.stop(0)


def main() -> int:
    workdir = tempfile.mkdtemp(prefix="sbs-retrain-test-")
    try:
        _, retrained_base = test_retrain_flips_label(workdir)
        test_modelstore_hotswap(workdir, retrained_base)
        test_grpc_hotswap(workdir, retrained_base)
        print("\nALL RETRAIN + HOT-SWAP CHECKS PASS")
        return 0
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
