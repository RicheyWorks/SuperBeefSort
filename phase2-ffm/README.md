# Phase 2 — Rust radix kernel via Panama FFM (proof-of-concept)

A working vertical slice of [Phase 2](../docs/HANDOFF.md): the JVM sorts a `long[]` by handing an
**off-heap `MemorySegment`** to a **Rust LSD radix sort** through the **Panama Foreign Function & Memory
API** (a downcall), then copies the result back. It proves the hard, risky part of Phase 2 — JVM↔native
interop with zero-copy off-heap data — end to end, before investing in the full Gradle module.

## Files

- **`radix.rs`** — an 8-bit-per-pass LSD radix sort over `u64`, exposed as `sbs_radix_sort_u64(*mut u64, usize)`
  via the C ABI. Has its own Rust unit tests (`rustc --test`).
- **`SbsRadixFfm.java`** — the FFM bridge: a `Linker` downcall handle, an `Arena`-allocated off-heap segment,
  signed→unsigned key mapping (`^ Long.MIN_VALUE`, matching the pure-Java `RadixSortStrategy`), and a
  differential check against `Arrays.sort`.

## Build & run

Needs a Rust compiler and **JDK 21+** (FFM is preview in 21, finalized in 22 — on 22+ drop `--enable-preview`):

```bash
rustc --test -O radix.rs -o radixtest && ./radixtest          # Rust unit tests
rustc --crate-type=cdylib -O radix.rs -o libsbsradix.so       # the kernel
javac --release 21 --enable-preview SbsRadixFfm.java
java  --enable-preview --enable-native-access=ALL-UNNAMED SbsRadixFfm
```

If the cdylib dynamically links the Rust std runtime, put that `.so`'s directory on `LD_LIBRARY_PATH` when
running (or build the kernel with a static std).

## Verified

Bootstrapped in a JRE-only sandbox (rustc 1.75 + JDK 21 extracted without root) and run:

```
FFM -> Rust radix: OK (300 random arrays incl. negatives, 0 mismatches)
example sorted: [-9223372036854775808, -3, -3, 0, 2, 5, 9, 9223372036854775807]
```

So: negatives, duplicates, and the `Long.MIN/MAX` extremes all sort correctly through the off-heap FFM path.

## What's left to productionize (the real Phase 2)

This slice deliberately stops at "the pipeline works". Turning it into a shipping strategy means:

1. **Gradle module `sbs-kernels-rust`** with a Java *toolchain* pinned to 21/22 (the rest of the build stays
   on 17), a task that runs `cargo`/`rustc` to produce the platform `cdylib`, and the `.so` packaged on the
   resource path.
2. **`RadixSortStrategy` variant** with `StrategyCapabilities.backingRuntime = RUST` and a guarded load:
   if the native lib is absent or FFM is unavailable, fall back to the existing **pure-Java radix** (the
   capability seam already supports this) — so the jar still runs everywhere.
3. **Off-heap `SortBuffer`** variant so large runs never touch the heap (the `SortBuffer` abstraction was
   designed for exactly this — see ARCHITECTURE §5.1).
4. **Benchmark** the native path vs Java radix (the FFM marshaling cost has to be earned back by the kernel;
   measure before defaulting to it — same discipline as the Phase 3 `EnsembleFeedBenchmark`).
5. **`parallel` Rust** (rayon) once the single-threaded path is integrated and measured.
