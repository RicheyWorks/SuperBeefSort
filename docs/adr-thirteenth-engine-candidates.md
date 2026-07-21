# Thirteenth-engine candidates — the provenance turn

Twelve engines close the organism as it was scoped: intake, index, store, plan, view, cache,
fleet, time-travel, atomicity, wire, cold storage, and the composition that proves them
together. WholeHog named its own successors — Rub and Sizzle — re-armed by what integration
finds. This record is the other axis: not "what does composition surface next" but "what does
the doctrine itself still owe." It weighs three candidates against the doctrines and picks one.

The organism's crown doctrine is **the log is the only truth; cold starts acceptable, wrong
data never**. Everything downstream is a rebuildable cache that fails loudly rather than guess.
But "wrong data never" is currently defended by CRC — which proves *bits did not rot*. It does
not prove *history was not rewritten*. A CRC passes on a segment an adversary re-encoded
cleanly. The organism can detect a corrupt archive; it cannot yet detect a *forged* one. That
is the seam this record opens.

## The candidates

| Name | Role | Born of | Doctrine standing |
|---|---|---|---|
| **Brand** | the provenance seal — a Merkle-chained integrity layer over the segment log; every record branded, every replica reconciled by proof, every archive verified un-tampered | CRC proved rot, never forgery | obeys all; opens one measured seam |
| **Corral** | identity on the wire — mTLS/token auth and per-scope access over SmokeSignal's loopback protocol | the wire's loud "no auth" | fills a named non-goal; no doctrine tension |
| **Roundup** | witnessed promotion — a single-witness lease so PitBoss's runbook promotion becomes automatic, without a consensus quorum | the fleet promotes by hand | brushes the loud "no consensus" non-goal; deferred |

**The pick is Brand.** Corral and Roundup are recorded with re-arming triggers below; Brand is
the one the crown doctrine actually demands, and the only one that obeys every doctrine without
bending one.

---

## Brand — the provenance seal

**Role.** A rolling Merkle chain folded over the segment log. Each appended record contributes a
leaf hash `H(seq ‖ key ‖ value ‖ prevLeaf)`; segments carry a Merkle root; the roots chain
across segments so the whole store reduces to one head digest. Brand is a **cache of hashes over
the log** — nothing authoritative, fully rebuildable from segments alone. It stores no data the
log doesn't already own; it stores the *proof* the log always implied.

**Born of.** CRC proved rot, never forgery. The organism could refuse a corrupt archive but not
an altered one; a replica could detect a gap but not a rewrite; DryAge could resurrect the past
but not *prove* the past it resurrected is the past that happened.

**What it gives, named by its consumers (seams are named by consumers, never speculative):**

- **`headDigest()` / `proofFor(seq)` — named by DryAge and Jerky.** An as-of read or an
  unpacked archive carries an inclusion proof against the head digest: O(log n) hashes prove a
  record belongs to the sealed history, not merely that its bytes are intact. A tampered archive
  fails the proof and *refuses to unpack* — the same "a corrupt archive refuses to unpack"
  doctrine, upgraded from rot-detection to forgery-detection.
- **`reconcile(theirDigest) → divergentRange` — named by PitBoss and replication.** Two stores
  compare Merkle nodes top-down and ship only the divergent leaf range. A gapped replica
  currently re-bootstraps by full re-ship; with Brand it syncs by proof in O(log n + Δ) instead
  of O(n). This is the one **performance seam**, and per *measure before cutting* it stays closed
  until a JMH number shows verified diff-sync beats re-bootstrap on a realistic gap — until then
  Brand ships the digests and proofs, and PitBoss keeps re-shipping.

**How it obeys each doctrine.**

- *Log is the only truth; every index is a cache.* Brand's chain is recomputable from segments
  with zero external state. Drop the whole Brand cache and `rebuild()` from the log restores the
  identical head digest. It is the doctrine, hashed.
- *Single writer, one level at a time.* One writer folds leaves on the tail thread — the same
  tail every Renderer view and Brine invalidation already rides. No new concurrency model.
- *Caller-cadenced control loops.* Brand owns no clock. Sealing advances as records arrive on the
  caller's cadence; verification runs when a consumer asks. The only threads remain SmokeHouse's.
- *Measure before cutting.* The diff-sync seam is gated on a JMH number (above). The sealing path
  itself is O(1) amortized per append (one hash, one chain link) and ships without a gate.
- *Seams named by consumers.* `proofFor`/`headDigest` named by DryAge+Jerky; `reconcile` named by
  PitBoss. The core grows no surface without a consumer.
- *Cold starts always acceptable; wrong data never.* A missing Brand cache cold-rebuilds from the
  log. A **failed proof is wrong data** — and Brand does exactly what the doctrine demands: refuse
  loudly (archive won't unpack, replica stays consistently stale and flags divergence, an as-of
  read fails rather than returns unproven history).
- *Replays are idempotent.* Re-folding a record the chain already contains is a no-op — the same
  last-writer-wins overlap argument the organism uses four times over, applied a fifth.
- *Oracle tests, seeded and deterministic.* See below.
- *Honest documentation of what doesn't work.* Non-goals stated loudly below.

**Oracle-test plan.** Every behavior against a brute-force reference. (1) `headDigest()` after any
op sequence equals a hash recomputed by a dead-simple full-scan fold over the same records —
seeded, deterministic. (2) `proofFor(seq)` verifies against the digest for every present record
and *fails* for every absent or altered one (flip a byte in a leaf → proof must reject). (3)
`reconcile` returns exactly the symmetric difference of two stores' records — checked against a
brute-force set diff over thousands of seeded divergence patterns, including empty, prefix,
suffix, and interleaved gaps. (4) `rebuild()` from segments reproduces the byte-identical head
digest. (5) **Composition (engine-thirteen joins the organism by joining WholeHog):** Brand seals
the one shared store while Renderer, Brine, Twine, DryAge, and the replicas ride the same tail;
the four-subscriber tail test gains a fifth subscriber whose assertion is "the head digest every
consumer observes agrees, and every archived/restored/replicated record proves against it."

**Non-goals, stated loudly.** Brand is *tamper-evidence, not tamper-prevention* — it proves the
log was altered; it does not stop a writer with disk access from rewriting and re-sealing (that
needs an external anchor or a signing key, which is Corral's axis, not Brand's). No signatures, no
external timestamp authority, no key management in the core — the leaf hash is unkeyed by default;
a keyed-MAC mode is a re-arming trigger, not a launch scope. Not a blockchain: single-writer, no
consensus, no proof-of-work — it is a Merkle *cache*, not a distributed ledger. Diff-sync ships
disabled until its JMH number lands.

**Re-arming triggers.** (a) Keyed/signed seals — armed when a consumer needs *authenticated*
provenance across a trust boundary (this is where Brand meets Corral). (b) Verified diff-sync —
armed by the JMH number above. (c) Proof-carrying wire frames — armed if SmokeSignal grows a
consumer that wants every shipped record to carry its inclusion proof.

---

## The deferred candidates, recorded honestly

**Corral (identity on the wire).** SmokeSignal ships with a loud "no auth on the wire." Corral
fences it: mTLS or token identity on the loopback protocol, per-scope read/write access, all at
the caller's cadence. No doctrine tension — it fills a stated non-goal cleanly. Deferred, not
rejected: **re-armed when a consumer needs the wire to leave the trusted host**, at which point
Corral and Brand's keyed-seal trigger arm together (identity + authenticated provenance are one
turn).

**Roundup (witnessed promotion).** PitBoss watches lag and owns a *manual* promotion runbook; the
organism's "no consensus, no failover" is a loud, deliberate non-goal. Roundup would add a
single external **witness lease** — not a Raft quorum — so a lagging primary's promotion becomes
automatic while preserving "a replica stays consistently stale" honesty. It is recorded because
it is the *minimal doctrine-safe* shape of the thing the doctrine forbids, and the distinction
matters: a witness lease is failover without consensus. **Held** — it still brushes the loud
non-goal, and the honest disposition is that manual promotion with a good runbook is acceptable
until an operator names an availability requirement the runbook can't meet. Re-armed by that
requirement, and only in the witness-lease shape (a quorum consensus engine stays out of the
organism).

**Columnar cold-scan (Jerky-internal, not a new engine).** A vectorized analytic scan over cold
archives is already named as deferred inside Jerky, waiting on its own JMH number. It is not a
thirteenth engine — it is Jerky earning a performance seam under *measure before cutting*.
Recorded here only to keep the map honest: the analytics gap is known and already has a home.

---

## Disposition

Brand is the thirteenth-engine candidate the crown doctrine actually owes: it turns "wrong data
never" from rot-detection into forgery-detection, and it does so as a rebuildable cache of hashes
that obeys every doctrine and opens exactly one measured seam. It joins the organism the only way
an engine can — by joining WholeHog's oracle as the fifth tail subscriber. Corral and Roundup are
recorded with named re-arming triggers; the columnar scan stays Jerky's to earn.

The organism has always converted its doctrines into features — DryAge and Jerky are "the log is
the only truth" made cold; Twine is "single writer" made atomic. Brand is **"wrong data never"
made provable**. That is the turn worth taking next.

## The records

Ecosystem-scope: this record sits beside the [fifth-engine](adr-fifth-engine-candidates.md) and
[seventh-engine](adr-seventh-engine-candidates.md) candidate records; WholeHog's successors (Rub,
Sizzle) remain the composition axis, Brand the provenance axis — orthogonal, both re-armed by
what they find. CSRBT-internal decisions continue in CSRBT/docs (ADR-001…013).
