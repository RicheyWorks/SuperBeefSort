"""Smoke + parity test for the SbsIntelligence server, fully in-process (no external service).

Cross-checks the server's Predict against an INDEPENDENT, unrounded feature derivation (the Java
LearnedModelStrategySelector.featureValue contract) + tree walk, over real mirror-generated profiles;
then exercises the Observe path and the non-SMART (no-advice) policy.

Run from this directory after ./gen.sh:   python3 smoke_test.py
Requires the Phase 4 mirror on the path (../sbs_mirror.py, ../gen_corpus.py) and ../sbs_selector_model.txt.
"""
import sys

import grpc

sys.path.insert(0, "..")  # tools/phase4 — for the validated mirror + feature derivation
import sbs_mirror as m
import gen_corpus  # _DIST_ORD + features() (training-time, rounded) for the label spread

import sbs_intelligence_pb2 as pb
import sbs_intelligence_pb2_grpc as rpc
import server as srv

MODEL_PATH = "../sbs_selector_model.txt"
MODEL = srv.load_model(MODEL_PATH)


def oracle(prof):
    """Independent re-statement of Java featureValue (UNROUNDED) + tree walk — the serving contract."""
    n = prof.size
    max_inv = n * (n - 1) / 2.0 if n > 1 else 0.0
    has_ks = prof.has_key_stats
    f = {
        "size": float(n), "sortedness_ratio": prof.sortedness_ratio,
        "has_duplicates": 1.0 if prof.has_duplicates else 0.0,
        "distinct_estimate": float(prof.distinct_estimate),
        "distinct_ratio": (prof.distinct_estimate / n) if n else 0.0,
        "has_key_stats": 1.0 if has_ks else 0.0,
        "key_span": float(prof.key_span) if has_ks else -1.0,
        "counting_feasible": 1.0 if (has_ks and prof.counting_feasible) else 0.0,
        "distribution_ord": float(gen_corpus._DIST_ORD[prof.distribution]),
        "longest_run": float(prof.longest_run),
        "longest_run_ratio": (prof.longest_run / n) if n else 0.0,
        "inversions": float(prof.inversions),
        "inversion_ratio": (prof.inversions / max_inv) if (prof.inversions >= 0 and max_inv > 0) else 0.0,
        "inversions_exact": 1.0 if prof.inversions_exact else 0.0,
        "has_byte_key": 1.0 if prof.has_byte_key else 0.0,
    }
    return srv.walk(MODEL, f)


def to_proto(prof):
    return pb.DataProfile(
        size=prof.size, sortedness_ratio=prof.sortedness_ratio, has_duplicates=bool(prof.has_duplicates),
        distinct_estimate=prof.distinct_estimate, has_key_stats=bool(prof.has_key_stats),
        key_span=int(prof.key_span) if prof.key_span is not None else 0,
        counting_feasible=bool(prof.counting_feasible) if prof.counting_feasible is not None else False,
        distribution_ord=gen_corpus._DIST_ORD[prof.distribution], longest_run=prof.longest_run,
        inversions=prof.inversions, inversions_exact=bool(prof.inversions_exact),
        has_byte_key=bool(prof.has_byte_key))


def main() -> int:
    corpus = "obs_smoke.jsonl"
    open(corpus, "w").close()
    server, port = srv.serve(MODEL_PATH, corpus, 0)
    stub = rpc.SbsIntelligenceStub(grpc.insecure_channel(f"127.0.0.1:{port}"))

    checked = mism = 0
    spread = {}
    prof = None
    for n in (100, 1000, 10000, 50000):
        for shape in m.SHAPES:
            for wk in (True, False):
                prof = m.profile(m.generate(shape, n, m.gate_seed(shape, n, 0, wk)), wk)
                exp_label, exp_conf = oracle(prof)
                r = stub.Predict(pb.PredictRequest(profile=to_proto(prof), policy="SMART"))
                checked += 1
                spread[exp_label] = spread.get(exp_label, 0) + 1
                if r.strategy_id != exp_label or abs(r.confidence - exp_conf) > 1e-9:
                    mism += 1
                    print(f"  MISMATCH {shape}/{n}/keys={wk}: "
                          f"server={r.strategy_id}({r.confidence:.3f}) oracle={exp_label}({exp_conf:.3f})")

    print(f"Predict parity: {checked - mism}/{checked} match (independent unrounded oracle); spread={spread}")

    r_stable = stub.Predict(pb.PredictRequest(profile=to_proto(prof), policy="STABLE"))
    assert r_stable.strategy_id == "", f"STABLE should yield no advice, got {r_stable.strategy_id!r}"
    print("non-SMART policy -> empty advice: OK")

    o1 = stub.Observe(pb.ObserveRequest(profile=to_proto(prof), chosen_strategy="counting",
            outcome=pb.SortOutcome(comparisons=1234, moves=5678, elapsed_nanos=999, peak_aux_bytes=4096)))
    o2 = stub.Observe(pb.ObserveRequest(profile=to_proto(prof), chosen_strategy="intro",
            outcome=pb.SortOutcome(comparisons=1, moves=2, elapsed_nanos=3, peak_aux_bytes=0)))
    assert o1.accepted and o2.accepted and o2.corpus_size == 2, (o1, o2)
    assert sum(1 for _ in open(corpus)) == 2
    print(f"Observe path: accepted, corpus_size={o2.corpus_size}: OK")

    server.stop(0)
    print("\nALL SMOKE CHECKS PASS" if mism == 0 else "\nPARITY MISMATCHES FOUND")
    return 0 if mism == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
