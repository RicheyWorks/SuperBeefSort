"""
validate_gate.py — reproduce Phase4DecisionGate's exact 324 workloads in the
Python mirror and assert the published oracle winner spread:

    counting 138, jdk.timsort 108, insertion 48, intro 30

(docs/adr-phase4-python-intelligence.md, action item 2). A match proves the mirror
(java.util.Random + profiler + metered strategies + oracle) is faithful, so a model
trained on a mirror-generated corpus is trained on real labels.
"""

from __future__ import annotations

import collections
import sys

import sbs_mirror as m

SIZES = [100, 500, 1000, 5000, 10000, 50000]
TRIALS = 3
EXPECTED = {"counting": 138, "jdk.timsort": 108, "insertion": 48, "intro": 30}


def main() -> int:
    winners = collections.Counter()
    n_workloads = 0
    for n in SIZES:
        for shape in m.SHAPES:
            for with_keys in (True, False):
                for trial in range(TRIALS):
                    seed = m.gate_seed(shape, n, trial, with_keys)
                    data = m.generate(shape, n, seed)
                    prof = m.profile(data, with_keys)
                    best, _costs = m.oracle(data, prof)
                    winners[best] += 1
                    n_workloads += 1

    print(f"Workloads: {n_workloads}  (expected 324)")
    print("Oracle winner spread (mirror):")
    for k in sorted(winners, key=lambda x: -winners[x]):
        exp = EXPECTED.get(k)
        tag = "" if exp is None else f"   (gate: {exp})"
        print(f"  {k:<14} {winners[k]:3d}{tag}")

    got = dict(winners)
    ok = got == EXPECTED
    print()
    print("MATCH" if ok else "MISMATCH", "vs published gate spread", EXPECTED)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
