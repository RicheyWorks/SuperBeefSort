# Phase 4 audit — 2026-06-21

Post-implementation review of the Phase 4 work (learned strategy selection): the Python
training pipeline, the in-process `LearnedModelStrategySelector` + `SelectorModel`, the Java
corpus dumper, and the two in-JVM JMH benchmarks. Scope: correctness, consistency, and
release-readiness across both working copies that contributed.

## Verdict

Ship after one cleanup (`err.txt`). Everything substantive checks out: the model is
deterministic and reproducible, all on-disk copies agree, the mirror still matches the gate
oracle, and the selector's gating logic is sound.

## Must fix before commit

- **Stray `err.txt` in the source tree** — `src/jmh/java/io/github/richeyworks/superbeefsort/bench/err.txt`,
  an accidental artifact from the benchmark rename (`mv … 2>err.txt`). Untracked and **not**
  covered by any `.gitignore`, so a directory-level `git add` would commit it. Delete it:
  `del src\jmh\java\io\github\richeyworks\superbeefsort\bench\err.txt`.

## Cleanups (not blocking)

- **`.claude/`** is untracked session tooling — keep it out of the commit (or add to `.gitignore`).
- **Benchmark overlap** — `SelectorInferenceLatencyBenchmark` (select-only) is fully subsumed by
  `SelectorBenchmark`'s group 1 (`costModelSelect`/`banditSelect`/`learnedSelect`). Both are kept and
  documented as complementary, but running both double-spends ~12–24 min on identical select-only
  numbers. Optional: drop `SelectorInferenceLatencyBenchmark`, or remove the select-only group from
  one of the two.
- **Hot-path `String.format` in `CostModelStrategySelector`** (pre-existing, surfaced by the bench):
  the TimSort branch builds its rationale with `String.format("%.2f", globalOrder)` on every
  `select()`, which is the ~150–200 ns `comparable_sorted` baseline (vs ~30 ns elsewhere). Selection
  is a per-job hot path; build the rationale lazily or with plain concatenation.
- **Unknown-feature handling (optional hardening)** — `LearnedModelStrategySelector.featureValue`
  throws `IllegalArgumentException` on an unrecognized feature name. If the model schema ever gains a
  feature without a matching Java update, `select()` throws rather than degrading to the delegate. A
  loud failure is defensible for a schema-contract violation; catch-and-defer would be more robust.

## Verified good

- **`build.gradle.kts` is intact** (both `phase4Gate` + `phase4Corpus` tasks, balanced) — an earlier
  "truncation at line 109" seen via the Linux mount was a stale-mount artifact; the successful
  `./gradlew build` and 12-minute JMH run confirm it configures cleanly.
- **Three model copies are byte-identical** — `tools/phase4/sbs_selector_model.txt`,
  `src/test/resources/sbs_selector_model.txt`, and the `EMBEDDED_MODEL` fallback constant in
  `SelectorInferenceLatencyBenchmark.java`.
- **Deterministic + reproducible** — retraining from the committed CSVs reproduces the committed model
  byte-for-byte (98.1% exact-match / 0.50% mean regret on the held-out gate set holds).
- **Mirror fidelity holds** — `validate_gate.py` still reproduces the gate oracle spread exactly
  (`counting 138, jdk.timsort 108, insertion 48, intro 30`).
- **`SelectorBenchmark` is correct** — uses the real engine API (`BeefSortEngine<>(selector, encoder)`,
  `engine.sort(...).sorted()`); `SortBuffer.of(list, …)` copies via `toArray()`, so the full-sort
  group is idempotent across JMH iterations.
- **No stale references** — the `SelectionLatencyBenchmark` → `SelectorInferenceLatencyBenchmark` rename
  left no dangling names anywhere in the tree.
- **Selector logic sound** — SMART-only override; size-gate (256) short-circuit confirmed by the n=200
  rows (learned ≈ cost-model); confidence margin (0.65) and registry+applicability checks gate every
  override; `observe` forwards only to a learning delegate.

## Measured selection latency (item 5, JDK 22.0.2, select-only)

| shape / n | cost-model | bandit | learned | learned overhead |
|---|---|---|---|---|
| int_clustered / 100000 | 41 ns | 984 ns | 567 ns | +526 ns |
| comparable_random / 100000 | 32 ns | 994 ns | 488 ns | +456 ns |
| comparable_sorted / 100000 | 200 ns | 922 ns | 354 ns | +154 ns |
| any / 200 (below gate) | — | — | ≈ cost-model | ≈ 0 (short-circuit) |

Inference overhead 150–530 ns is ~0.001–0.05% of the millisecond-class sort it drives, and the learned
selector is ~2× faster than the bandit while delivering far better picks — amortization confirmed.
