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

## Continual learning (follow-up)

Periodically retrain from the accumulated `observations.jsonl` with `../train_selector.py` and hot-swap the
served model; `PredictResponse.model_version` surfaces which model answered. (The first increment serves a
static model and accumulates the corpus; the retrain/hot-swap loop is the next step.)

## Status

- [x] Proto contract (`src/main/proto/sbs_intelligence.proto`)
- [x] Python server: serve flat tree (Predict) + ingest corpus (Observe) — verified in-sandbox (72/72 parity)
- [ ] Java `RemoteStrategySelector` (size gate + circuit breaker + local fallback) + build wiring
      (protobuf gradle plugin + grpc-java deps) — host-built, the next increment
- [ ] Retrain/hot-swap loop for continual learning
