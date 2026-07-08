# Hardening audit — SuperBeefSort

**Date:** 2026-07-08 · **Scope:** main sources + sbs-kernels-rust + sbs-intelligence-client +
tools/phase4/service (spill I/O, FFI boundary, network surfaces, allocation guards, and the 2026-07-07
integration layer — audited with the same severity as everything else). Static analysis at commit
`79f7867`; no runtime penetration testing. Companion: `CSRBT/docs/hardening-audit-2026-07-08.md`.

> **Remediation status (same day):** M-1 fixed (`ExternalMergeSorter.sortAndFeed` now rejects a
> bounded feed into a windowless target), M-2 fixed (`TreeEventBridge.onEvent` catches observer
> faults; CSRBT's `emit` hardened in tandem), M-3 fixed (plaintext constructors documented
> loopback-only with the TLS path spelled out). **L-tier closed 2026-07-08:** L-1
> (`spillDir(...)` on the external builder / `ExternalMergeSorter` / `SpillFile`), L-2
> (`checked_mul` in `sbs_radix_sort_keyed`; ScatterPtr's Send/Sync justification was already
> pinned), L-3 (`CsrbtTarget` threading contract documented), L-4 (`PrecisionFeeder` cost warning).

## What's already hardened well

The dangerous corners are guarded. `CountingSortStrategy` refuses key spans ≥ 2²⁴ instead of allocating
attacker-sized arrays. Spill files come from `Files.createTempFile` (owner-only permissions on POSIX),
are deleted in `finally` blocks *and* registered `deleteOnExit`. The Rust FFI entry points null-check
and `count < 2`-check before touching memory, document their safety contracts against the Java caller's
confined-`Arena` allocation, and keep panics out of the exported paths. `RemoteStrategySelector` is
genuinely defensive: per-call deadline, circuit breaker, and — critically — remote advice is validated
against the local registry *and* capability gates before it can influence a sort, so a compromised
intelligence service cannot select a strategy the data can't support. The Python service binds
`127.0.0.1` only, and the phase-4 tooling contains no `pickle`/`eval`/dynamic execution (the one
`subprocess` call is a test invoking `protoc` with a fixed argv). Feeders are iterative (no recursion
overflow on huge runs), and the comparator is captured in `CsrbtTarget` so sort order and tree order
cannot silently diverge.

## Findings

### M-1 · `ExternalMergeSorter.sortAndFeed` still has the silent-window bug (Medium)

`if (maxSize > 0 && target.supportsWindow())` — the exact Gap-8 shape fixed in `StreamingFeeder` on
2026-07-07 survives here: an out-of-core feed asked to bound a windowless target quietly streams
unbounded. For the *external* sorter the stakes are higher — the whole point of that path is data too
big for memory. **Recommendation:** throw `IllegalArgumentException`, mirroring `StreamingFeeder`.

### M-2 · `TreeEventBridge` forwards observer exceptions into CSRBT's write path (Medium)

The bridge (2026-07-07, ours) calls `observer.onEvent(...)` with no try/catch, and CSRBT invokes the
listener while holding its write locks. A throwing `SortObserver` therefore fails tree writes it had no
business touching. This is the SuperBeefSort half of CSRBT finding M-1. **Recommendation:**
catch-and-drop inside `TreeEventBridge.onEvent` — observability must never be able to break the data
plane.

### M-3 · Intelligence channel is plaintext by construction (Medium)

`GrpcIntelligenceClient(String target, …)` builds the channel with `.usePlaintext()`. Correct for the
loopback deployment it was designed for — but the constructor accepts any `host:port`, and pointed
across a network it ships data profiles out and accepts strategy advice back with no transport
integrity (the registry validation above limits blast radius, but observation exfiltration and advice
tampering remain). **Recommendation:** keep plaintext for the loopback convenience ctor but document it
as loopback-only, and expose the existing `ManagedChannel` ctor as the TLS path in the javadoc.

### L-1 · Spill data confidentiality and lifecycle (Low)

Spills write user data unencrypted to the system temp directory, and `deleteOnExit` accumulates one
JVM-lifetime reference per spill file — a slow leak in a long-running process that spills often
(deletion in `finally` makes the hook redundant in the normal path). **Recommendation:** allow a
caller-supplied spill directory (locked-down or ephemeral), and note the confidentiality property in
`ExternalMergeSorter`'s javadoc.

### L-2 · Rust FFI arithmetic and marker traits (Low)

`sbs_radix_sort_keyed` computes `count * 2` unchecked — harmless on 64-bit (Java counts are
`int`-bounded) but a usize overflow on a hypothetical 32-bit target; a `checked_mul` costs nothing.
`unsafe impl Send/Sync for ScatterPtr` is a manually asserted invariant — currently sound under the
"confined arena, no concurrent access" contract, but it's the one place a future refactor could break
memory safety without the compiler noticing. **Recommendation:** `checked_mul` + a comment pinning the
Send/Sync justification to the contract it depends on.

### L-3 · `CsrbtTarget`'s mutable wiring is not thread-safe (Low)

`observedBy`/`withHealthHook` (2026-07-07, ours) set plain fields on an otherwise immutable class.
Fine under the documented single-threaded feed cadence — the fan-out parallelism lives inside CSRBT and
never touches the target — but a caller sharing one `CsrbtTarget` across threads gets unsynchronized
publication. **Recommendation:** document the single-threaded contract on the class javadoc (matches
CSRBT's `RollingWorkloadMonitor` "not thread-safe by design").

### L-4 · `PrecisionFeeder` × health hooks is quadratic by construction (Low)

Health per insert was always the documented "slowest feeder" trade — but two 2026-07-07 additions raise
the ceiling: `OrderedSet.selfRepair` is an O(n) rebuild (⇒ O(n²) feed), and the new ensemble
`healthHook()` runs a full failover/quarantine/heal cadence per insert. On a large run this is a
self-inflicted denial of service, and repeated self-repair also discards a splay member's learned
layout. **Recommendation:** javadoc warning with a size guideline, or a batch floor above which
`PrecisionFeeder` refuses and points at `HealthGatedFeeder`.

### I-1 · Adaptation layer is caller-cadenced, single-threaded (Info)

`WorkloadAdaptation`, `EnsembleAdaptation`, `EvolutionAdaptation` keep unsynchronized op counters and
wrap CSRBT controllers that are single-threaded by contract. Current wiring is correct (monitors are
only ever touched from the feeding/calling thread). The invariant should stay stated wherever a new
adapter is added.

### I-2 · Model hot-swap trusts the model file (Info)

The phase-4 service file-watches `model_path` and hot-swaps on change; anyone with write access to that
path steers strategy advice. Registry + capability validation on the Java side bounds the damage to
"legal but suboptimal strategy," which is the right containment. Local file permissions are the control;
nothing to change in code.

## Suggested order

M-1 and M-2 are one-file fixes and should go in together (M-2's twin lives in CSRBT's report). M-3 is a
javadoc + constructor-doc change. L-tier items are next-touch notes.
