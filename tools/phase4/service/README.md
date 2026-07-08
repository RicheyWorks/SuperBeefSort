# SbsIntelligence — Phase 4b gRPC service

The optional runtime learned-selection service from `docs/adr-phase4-python-intelligence.md` (action item 6).
Phase 4a put the trained decision tree **in-process** (`select/LearnedModelStrategySelector` reading the flat
`sbs_selector_model.txt`). Phase 4b stands the same model up **behind gRPC** so it can do what in-process
can't: *continual, fleet-wide* learning — many engine processes feed their `observe()` stream to one service
that retrains and hot-swaps the model, with no JVM redeploy.

It is strictly **additive and optional**: the engine never depends on it (see "Fallback-first" below).

## Architecture

```
  Java engine                                   Python service (this dir)
  ┌──────────────────────────┐    Predict       ┌───────────────────────────────┐
  │ RemoteStrategySelector    │ ───────────────▶ │ SbsIntelligence.Predict        │
  │  (size gate + breaker,    │ ◀─────────────── │   derive features -> tree walk │
  │   wraps a local delegate) │  strategy,conf   │   (= Java SelectorModel walk)  │
  │                           │    Observe        │ SbsIntelligence.Observe        │
  │  engine.observe(...) ────▶│ ───────────────▶ │   append -> observations.jsonl │
  └──────────────────────────┘  (fire & forget)  │   (corpus for continual retrain)│
                                                  └───────────────────────────────┘
```

- **Proto** `src/main/proto/sbs_intelligence.proto` is the single source of truth (Java build + this service
  both generate from it). The client sends the **raw** `DataProfile` fields; the **server** derives the 15-feature
  model vector (`server.derive`, byte-for-byte `tools/phase4/gen_corpus.features` /
  `LearnedModelStrategySelector.featureValue`). Putting derivation in one place keeps training and serving in
  feature lockstep — the parity risk the ADR flagged for the networked path.
- **Predict** advises only under `SMART` (other policies → empty `strategy_id` = "no advice").
- **Observe** appends `{profile, chosen, outcome}` to `observations.jsonl`; that file is the corpus for the
  retrain loop.

## Fallback-first (the Java client, next increment)

`RemoteStrategySelector` mirrors `LearnedModelStrategySelector`'s gating and adds a circuit breaker, so the
engine stays correct when the service is down/slow/wrong:

1. **Policy/size gate** — only consult the service under `SMART` and `size >= sizeGate`; below it, the local
   delegate's pick verbatim (no RPC).
2. **Deadline + circuit breaker** — `Predict` runs with a short deadline; on timeout/error, increment a failure
   counter and **fall back to the local delegate** (`LearnedModelStrategySelector` or `CostModelStrategySelector`).
   After N consecutive failures the breaker **opens** (skip the RPC entirely, all-local) and **half-opens** after
   a cooldown to probe recovery.
3. **Advisory only** — override the delegate only when `confidence >= margin` and the predicted strategy is
   registered + applicable; otherwise keep the delegate's plan.
4. **Observe is best-effort** — fire-and-forget; a failed `Observe` never affects the sort.

Net: a missing or unhealthy service leaves selection byte-for-byte the local path — the engine's standard
capability-fallback discipline.

## Run it

```sh
pip install grpcio grpcio-tools     # one-time
./gen.sh                            # generate sbs_intelligence_pb2*.py from the canonical proto
python3 smoke_test.py               # in-process parity + Observe + non-SMART checks
python3 server.py                   # serve on 127.0.0.1:50051 (model ../sbs_selector_model.txt)
#   python3 server.py [model_path] [corpus_path] [port]
```

`smoke_test.py` cross-checks `Predict` against an **independent, unrounded** feature derivation + tree walk
over real mirror profiles — verified **72/72** across 4 sizes × 9 shapes × 2 key modes (all four model classes
exercised), plus the Observe and non-SMART paths.

## Continual learning — the retrain/hot-swap loop

`Observe` accumulates production telemetry in `observations.jsonl`; `retrain.py` turns it into a new model and
a running server hot-swaps it with **no restart**.

```sh
# 1. retrain from the corpus, writing straight onto the served model (atomic os.replace)
python3 retrain.py observations.jsonl --seed ../phase4_train.csv --out ../sbs_selector_model
# 2. a server started with the watcher (the default in `python3 server.py`) hot-swaps within poll_secs;
#    or signal an explicit reload:  kill -HUP <server-pid>
python3 retrain_test.py     # end-to-end: retrain relabel/flip + ModelStore reload + live gRPC hot-swap
```

**Labeling.** `retrain.py` buckets each observation into a coarse *context* (size decade × key-stats ×
counting-feasibility × distribution × byte-key × sortedness band) and labels the context by its **empirically
cheapest** strategy (mean `comparisons + moves` over the observations that fell in it), then **unions** those
production rows with the oracle-labeled seed corpus (`phase4_train.csv`) so contexts production never exercised
keep their oracle label — no catastrophic forgetting. `--obs-weight` up-weights production rows vs the seed. It
fits the same depth-8 tree as `train_selector.py` and reuses its exporters, so the artifact is byte-compatible
with `server.load_model` and Java `SelectorModel`. This is **offline policy improvement** (refine where
production gives multi-arm evidence), not online learning: Observe only ever records the cost of the arm that
*was pulled*, so a context needs evidence for ≥2 strategies (a fleet running a mix of selectors) before its
label can actually move.

**Hot-swap.** `server.ModelStore` holds the active model; `current()` is a torn-read-safe single reference read,
`reload()` re-parses under a lock. A daemon **watcher** thread polls the model file's `(mtime, size)` and reloads
on change; **SIGHUP** reloads too. `model_version` carries a content hash (`<type>.s<schema>.n<nodes>.<sha1>`),
so a swap is visible in every `PredictResponse`. `retrain.py`'s atomic `os.replace` guarantees the watcher only
ever observes a complete old or complete new file, never a half-written one.

## Status

- [x] Proto contract (`src/main/proto/sbs_intelligence.proto`)
- [x] Python server: serve flat tree (Predict) + ingest corpus (Observe) — verified in-sandbox (72/72 parity)
- [x] Java client (core, grpc-free): `select/RemoteStrategySelector` (SMART + size gate + confidence +
      applicability, else delegate) + `select/IntelligenceClient` seam + pure `intelligence/CircuitBreaker` —
      unit-tested (`RemoteStrategySelectorTest`, `CircuitBreakerTest`), run in the default build
- [x] gRPC transport: opt-in `sbs-intelligence-client` module (`-PwithIntelligence`) with
      `GrpcIntelligenceClient` (per-call deadline + breaker, never throws). Generates Java stubs from the proto.
      **Host-build to verify** (grpc 1.62.2 / protobuf 3.25.3 / protobuf-gradle 0.9.4):
      `./gradlew :sbs-intelligence-client:build -PwithIntelligence`
- [x] Retrain/hot-swap loop: `retrain.py` (observations → versioned model, atomic os.replace) + `server.py`
      `ModelStore` hot-swap (file-watch + SIGHUP, content-hash `model_version`). Verified in-sandbox by
      `retrain_test.py` (retrain relabel/flip + ModelStore reload + live gRPC hot-swap); `smoke_test.py` 72/72.
