//! SuperBeefSort Phase 2 kernel: entropy-aware LSD radix sort via Panama FFM.
//!
//! Exposes one C ABI function: [`sbs_radix_sort_keyed`]. The Java caller:
//!   1. Sign-flips each item's encoded key (`key ^ Long.MIN_VALUE`) for correct unsigned ordering.
//!   2. Packs n (key, original-index) pairs interleaved: `data[2*i] = key, data[2*i+1] = index`.
//!   3. Calls `sbs_radix_sort_keyed(ptr, n)` to sort the pairs stably by key.
//!   4. Reads the sorted index field to reconstruct the item permutation.
//!
//! The sort plan mirrors Java's `RadixPlan.forWidth`: find min/max of keys, offset-by-min so only
//! the significant bits of the key *range* vary, then pick the bits-per-pass that minimises
//! `passes * (n + 2^b)` — identical to the Java formula, so narrow high-magnitude keys sort in a
//! single pass just as the pure-Java path does.
//!
//! Build:  cargo build --release   (produces sbsradix.dll / libsbsradix.so / libsbsradix.dylib)
//! Test:   cargo test

use rayon::prelude::*;

/// Mirror of `RadixPlan.forWidth`: picks bits-per-pass minimising `passes * (n + 2^b)`.
/// Returns `(bits_per_pass, passes)`. Zero passes when all keys are equal.
fn radix_plan(significant_bits: u32, n: usize) -> (u32, u32) {
    if significant_bits == 0 {
        return (1, 0);
    }
    let items = n.max(1) as f64;
    let mut best_bits = 1u32;
    let mut best_cost = f64::MAX;
    for b in 1u32..=16 {
        let passes = (significant_bits + b - 1) / b;
        let cost = passes as f64 * (items + (1u64 << b) as f64);
        if cost < best_cost {
            best_cost = cost;
            best_bits = b;
        }
    }
    let passes = (significant_bits + best_bits - 1) / best_bits;
    (best_bits, passes)
}

/// Stable LSD radix sort of `n` (key, payload) pairs packed in `data[0..2n]` as
/// `[key0, pay0, key1, pay1, ...]`. Applies offset-by-min + adaptive plan internally.
fn sort_keyed_flat(data: &mut [u64], n: usize) {
    if n < 2 {
        return;
    }

    // Pass 1: find min/max of keys (stride 2 — keys sit at even indices)
    let mut min_key = data[0];
    let mut max_key = data[0];
    for i in 0..n {
        let k = data[2 * i];
        if k < min_key {
            min_key = k;
        }
        if k > max_key {
            max_key = k;
        }
    }

    let range = max_key.wrapping_sub(min_key);
    let significant_bits = if range == 0 { 0 } else { 64 - range.leading_zeros() };
    let (bits_per_pass, passes) = radix_plan(significant_bits, n);
    if passes == 0 {
        return; // all keys equal — already in stable order
    }

    let radix = 1usize << bits_per_pass;
    let mask = radix as u64 - 1;

    // Working buffers: flat layout [offset_key0, pay0, offset_key1, pay1, ...]
    let mut src = vec![0u64; 2 * n];
    for i in 0..n {
        src[2 * i] = data[2 * i].wrapping_sub(min_key); // offset-by-min: reduce to range
        src[2 * i + 1] = data[2 * i + 1];               // payload (original index) unchanged
    }
    let mut dst = vec![0u64; 2 * n];

    for pass in 0..passes {
        let shift = pass * bits_per_pass;

        // Count digit frequencies (1-indexed for prefix-sum trick)
        let mut count = vec![0usize; radix + 1];
        for i in 0..n {
            let digit = ((src[2 * i] >> shift) & mask) as usize;
            count[digit + 1] += 1;
        }
        // Prefix sum → starting positions
        for d in 0..radix {
            count[d + 1] += count[d];
        }
        // Stable scatter: iterate src in forward order to preserve relative order
        for i in 0..n {
            let digit = ((src[2 * i] >> shift) & mask) as usize;
            let pos = count[digit];
            dst[2 * pos] = src[2 * i];
            dst[2 * pos + 1] = src[2 * i + 1];
            count[digit] += 1;
        }
        std::mem::swap(&mut src, &mut dst);
    }

    // Write back: un-offset keys, copy payload
    for i in 0..n {
        data[2 * i] = src[2 * i].wrapping_add(min_key);
        data[2 * i + 1] = src[2 * i + 1];
    }
}

/// Stable LSD radix sort of `n` flat u64 keys in `data[0..n]` — the stride-1 sibling of
/// [`sort_keyed_flat`], with no payload field. Same offset-by-min + adaptive [`radix_plan`]. Used by
/// the off-heap long fast path, where the values *are* the keys, so no index payload is carried (half
/// the bytes, no permutation). Stable for equal keys (forward scatter), though without a payload that
/// is only observable as "already-sorted equal runs stay put".
fn sort_flat(data: &mut [u64], n: usize) {
    if n < 2 {
        return;
    }

    let mut min_key = data[0];
    let mut max_key = data[0];
    for i in 0..n {
        let k = data[i];
        if k < min_key {
            min_key = k;
        }
        if k > max_key {
            max_key = k;
        }
    }

    let range = max_key.wrapping_sub(min_key);
    let significant_bits = if range == 0 { 0 } else { 64 - range.leading_zeros() };
    let (bits_per_pass, passes) = radix_plan(significant_bits, n);
    if passes == 0 {
        return; // all keys equal — already in order
    }

    let radix = 1usize << bits_per_pass;
    let mask = radix as u64 - 1;

    let mut src = vec![0u64; n];
    for i in 0..n {
        src[i] = data[i].wrapping_sub(min_key); // offset-by-min
    }
    let mut dst = vec![0u64; n];

    for pass in 0..passes {
        let shift = pass * bits_per_pass;
        let mut count = vec![0usize; radix + 1];
        for i in 0..n {
            let digit = ((src[i] >> shift) & mask) as usize;
            count[digit + 1] += 1;
        }
        for d in 0..radix {
            count[d + 1] += count[d];
        }
        for i in 0..n {
            let digit = ((src[i] >> shift) & mask) as usize;
            let pos = count[digit];
            dst[pos] = src[i];
            count[digit] += 1;
        }
        std::mem::swap(&mut src, &mut dst);
    }

    for i in 0..n {
        data[i] = src[i].wrapping_add(min_key);
    }
}

/// A `*mut u64` that is `Send`/`Sync` so the parallel scatter closure can write through it from
/// worker threads. Safety is upheld by the caller: each thread only writes a disjoint, in-bounds
/// index range, so there is no aliasing despite the shared base pointer.
struct ScatterPtr(*mut u64);
unsafe impl Send for ScatterPtr {}
unsafe impl Sync for ScatterPtr {}
impl ScatterPtr {
    // Accessed via a &self method (not the `.0` field) so the parallel closure captures the whole
    // Sync wrapper, not the bare `*mut u64` field (Rust 2021 disjoint-capture would pick the field,
    // which is !Sync). Returns the shared base pointer; threads write only disjoint index ranges.
    #[inline]
    fn raw(&self) -> *mut u64 {
        self.0
    }
}

/// Rayon-parallel variant of [`sort_flat`] — Phase 2 branch B (ADR docs/adr-phase2-offheap-sortbuffer.md).
/// Same stable LSD radix + offset-by-min + adaptive plan, but each pass runs as: (1) parallel per-chunk
/// histograms, (2) a cheap sequential prefix that hands each (chunk, digit) a disjoint output region —
/// digit-major then chunk-major, so stability is preserved — and (3) a parallel scatter where each chunk
/// writes only into its own regions. Falls back to the sequential [`sort_flat`] below a threshold where
/// thread overhead would dominate.
fn sort_flat_parallel(data: &mut [u64], n: usize) {
    const PAR_THRESHOLD: usize = 1 << 16; // 65_536 — below this, thread setup costs more than it saves
    if n < PAR_THRESHOLD {
        sort_flat(data, n);
        return;
    }

    let mut min_key = data[0];
    let mut max_key = data[0];
    for i in 0..n {
        let k = data[i];
        if k < min_key {
            min_key = k;
        }
        if k > max_key {
            max_key = k;
        }
    }
    let range = max_key.wrapping_sub(min_key);
    let significant_bits = if range == 0 { 0 } else { 64 - range.leading_zeros() };
    let (plan_bits, plan_passes) = radix_plan(significant_bits, n);
    if plan_passes == 0 {
        return; // all keys equal — already in order
    }
    // Cap the radix for the parallel path. Per-pass overhead here (a per-chunk histogram of `radix`
    // counts, the O(radix × num_chunks) sequential offset prefix, and a per-chunk cursor clone) scales
    // with radix, so the entropy plan's large radix — which minimises the *sequential* pass count —
    // would dwarf any parallel gain. 8 bits/pass keeps those structures tiny (256 counts) at the cost
    // of a few more passes, which parallelise well.
    let bits_per_pass = plan_bits.min(8);
    let passes = (significant_bits + bits_per_pass - 1) / bits_per_pass;
    let radix = 1usize << bits_per_pass;
    let mask = radix as u64 - 1;

    let mut src = vec![0u64; n];
    src.par_iter_mut()
        .zip(data.par_iter())
        .for_each(|(s, &d)| *s = d.wrapping_sub(min_key)); // offset-by-min
    let mut dst = vec![0u64; n];

    let p = rayon::current_num_threads().max(1);
    let chunk = (n + p - 1) / p; // ceil; par_chunks yields ceil(n/chunk) chunks
    let num_chunks = (n + chunk - 1) / chunk;

    for pass in 0..passes {
        let shift = pass * bits_per_pass;

        // 1) parallel per-chunk histograms (read-only on src — safe)
        let hists: Vec<Vec<usize>> = src
            .par_chunks(chunk)
            .map(|c| {
                let mut h = vec![0usize; radix];
                for &v in c {
                    h[((v >> shift) & mask) as usize] += 1;
                }
                h
            })
            .collect();

        // 2) sequential global offsets, digit-major then chunk-major => stable
        let mut base = vec![vec![0usize; radix]; num_chunks];
        let mut prefix = 0usize;
        for d in 0..radix {
            for c in 0..num_chunks {
                base[c][d] = prefix;
                prefix += hists[c][d];
            }
        }

        // 3) parallel scatter into disjoint regions of dst
        let dst_ptr = ScatterPtr(dst.as_mut_ptr());
        src.par_chunks(chunk).enumerate().for_each(|(ci, c)| {
            let ptr = dst_ptr.raw();
            let mut cur = base[ci].clone(); // per-thread cursors, seeded from this chunk's bases
            for &v in c {
                let d = ((v >> shift) & mask) as usize;
                let pos = cur[d];
                cur[d] += 1;
                // Safety: pos in [base[ci][d], base[ci][d]+hists[ci][d]) ⊂ [0, n); these ranges are
                // disjoint across all (ci, d), so concurrent writes through dst_ptr never alias.
                unsafe {
                    *ptr.add(pos) = v;
                }
            }
        });

        std::mem::swap(&mut src, &mut dst);
    }

    data.par_iter_mut()
        .zip(src.par_iter())
        .for_each(|(d, &s)| *d = s.wrapping_add(min_key)); // un-offset
}

/// Stable sort of `count` (key, payload) pairs packed as interleaved u64 values.
///
/// `ptr` points to `count * 2` consecutive u64 values: `[key0, pay0, key1, pay1, ...]`.
/// Keys are u64 (the Java caller XORs `Long.MIN_VALUE` to convert signed long → unsigned order).
/// On return: pairs are in ascending key order; equal-key pairs keep their original relative order.
/// No-op for null pointer or `count < 2`.
#[no_mangle]
pub extern "C" fn sbs_radix_sort_keyed(ptr: *mut u64, count: usize) {
    if ptr.is_null() || count < 2 {
        return;
    }
    // Safety: the Java caller allocates a confined `Arena` of exactly `count * 2 * 8` bytes,
    // aligned to 8 bytes, and does not access the segment concurrently with this call.
    let data = unsafe { std::slice::from_raw_parts_mut(ptr, count * 2) };
    sort_keyed_flat(data, count);
}

/// Stable sort of `count` flat u64 keys (no payload field) packed consecutively in `ptr[0..count]`.
///
/// Keys are u64 (the Java caller XORs `Long.MIN_VALUE` for signed→unsigned ordering). Sorted ascending
/// in place; equal keys keep their relative order. No-op for a null pointer or `count < 2`. This is the
/// entry for the off-heap long fast path, which bulk-copies a `long[]` into a `MemorySegment` and sorts
/// it in place — no per-element marshaling and no (key, index) pairing.
#[no_mangle]
pub extern "C" fn sbs_radix_sort_longs(ptr: *mut u64, count: usize) {
    if ptr.is_null() || count < 2 {
        return;
    }
    // Safety: the Java caller allocates a confined `Arena` of exactly `count * 8` bytes, 8-byte aligned,
    // and does not access the segment concurrently with this call.
    let data = unsafe { std::slice::from_raw_parts_mut(ptr, count) };
    sort_flat(data, count);
}

/// Rayon-parallel stable sort of `count` flat u64 keys in place — same contract as
/// [`sbs_radix_sort_longs`], but the histogram + scatter run across rayon's thread pool (Phase 2
/// branch B). No-op for a null pointer or `count < 2`; below an internal threshold it falls back to
/// the sequential path, so it is always safe to call.
#[no_mangle]
pub extern "C" fn sbs_radix_sort_longs_par(ptr: *mut u64, count: usize) {
    if ptr.is_null() || count < 2 {
        return;
    }
    // Safety: as in sbs_radix_sort_longs — the Java caller owns a confined, 8-byte-aligned segment of
    // exactly count u64s and does not touch it concurrently with this call.
    let data = unsafe { std::slice::from_raw_parts_mut(ptr, count) };
    sort_flat_parallel(data, count);
}

#[cfg(test)]
mod tests {
    use super::*;

    fn do_sort(pairs: &mut Vec<(u64, u64)>) {
        let n = pairs.len();
        if n < 2 {
            return;
        }
        let mut flat: Vec<u64> = pairs.iter().flat_map(|&(k, p)| [k, p]).collect();
        sort_keyed_flat(&mut flat, n);
        for (i, pair) in pairs.iter_mut().enumerate() {
            *pair = (flat[2 * i], flat[2 * i + 1]);
        }
    }

    fn reference(pairs: &mut Vec<(u64, u64)>) {
        pairs.sort_by(|a, b| a.0.cmp(&b.0).then(a.1.cmp(&b.1)));
    }

    #[test]
    fn sorts_random_inputs() {
        let mut seed = 0x9E3779B97F4A7C15u64;
        let next = |s: &mut u64| {
            *s ^= *s << 13;
            *s ^= *s >> 7;
            *s ^= *s << 17;
            *s % 100
        };
        for trial in 0..500 {
            let n = (trial * 7 + 3) % 300 + 2;
            let mut pairs: Vec<(u64, u64)> = (0..n as u64).map(|i| (next(&mut seed), i)).collect();
            let mut want = pairs.clone();
            reference(&mut want);
            do_sort(&mut pairs);
            assert_eq!(pairs, want, "trial={trial}, n={n}");
        }
    }

    #[test]
    fn offset_by_min_narrows_passes() {
        // High-magnitude narrow range: should need just 1-2 passes after offset
        let base = 1_000_000_000u64;
        let mut pairs: Vec<(u64, u64)> =
            vec![(base + 5, 0), (base + 1, 1), (base + 3, 2), (base + 2, 3), (base, 4)];
        let mut want = pairs.clone();
        reference(&mut want);
        do_sort(&mut pairs);
        assert_eq!(pairs, want);
    }

    #[test]
    fn stable_with_duplicate_keys() {
        let mut pairs: Vec<(u64, u64)> =
            vec![(3, 0), (1, 1), (2, 2), (1, 3), (3, 4), (2, 5), (1, 6)];
        let expected: Vec<(u64, u64)> =
            vec![(1, 1), (1, 3), (1, 6), (2, 2), (2, 5), (3, 0), (3, 4)];
        do_sort(&mut pairs);
        assert_eq!(pairs, expected);
    }

    #[test]
    fn handles_edge_cases() {
        let mut empty: Vec<(u64, u64)> = vec![];
        do_sort(&mut empty);

        let mut one = vec![(42u64, 0u64)];
        do_sort(&mut one);
        assert_eq!(one, vec![(42, 0)]);

        // All equal: stable order preserved (ascending index)
        let mut all_eq: Vec<(u64, u64)> = (0..8u64).map(|i| (99, i)).collect();
        let want = all_eq.clone();
        do_sort(&mut all_eq);
        assert_eq!(all_eq, want);
    }

    #[test]
    fn handles_full_u64_range() {
        let mut pairs = vec![(u64::MAX, 0u64), (0, 1), (u64::MAX / 2, 2)];
        let mut want = pairs.clone();
        reference(&mut want);
        do_sort(&mut pairs);
        assert_eq!(pairs, want);
    }

    #[test]
    fn c_abi_entry_is_safe_for_null_and_short() {
        sbs_radix_sort_keyed(std::ptr::null_mut(), 0);
        sbs_radix_sort_keyed(std::ptr::null_mut(), 5);
        let mut v = vec![9u64, 0u64]; // one pair
        sbs_radix_sort_keyed(v.as_mut_ptr(), 1); // no-op
        assert_eq!(v, vec![9, 0]);
    }

    // ── flat-long entry (off-heap fast path) ──────────────────────────────────────────────────

    #[test]
    fn flat_sorts_random() {
        let mut seed = 0x1234_5678_9ABC_DEF0u64;
        let next = |s: &mut u64| {
            *s ^= *s << 13;
            *s ^= *s >> 7;
            *s ^= *s << 17;
            *s % 1000
        };
        for trial in 0..300 {
            let n = (trial * 5 + 2) % 250 + 2;
            let mut v: Vec<u64> = (0..n).map(|_| next(&mut seed)).collect();
            let mut want = v.clone();
            want.sort();
            sort_flat(&mut v, n);
            assert_eq!(v, want, "trial={trial}, n={n}");
        }
    }

    #[test]
    fn flat_edges_and_high_magnitude() {
        let mut empty: Vec<u64> = vec![];
        sort_flat(&mut empty, 0);

        let base = 1_000_000_000u64;
        let mut v = vec![base + 5, base + 1, base + 3, base + 2, base];
        let mut want = v.clone();
        want.sort();
        let n = v.len();
        sort_flat(&mut v, n);
        assert_eq!(v, want);

        let mut full = vec![u64::MAX, 0, u64::MAX / 2, 7];
        let mut wf = full.clone();
        wf.sort();
        sort_flat(&mut full, 4);
        assert_eq!(full, wf);

        let mut all_eq = vec![42u64; 8];
        let we = all_eq.clone();
        sort_flat(&mut all_eq, 8);
        assert_eq!(all_eq, we);
    }

    #[test]
    fn flat_c_abi_safe_for_null_and_short() {
        sbs_radix_sort_longs(std::ptr::null_mut(), 0);
        sbs_radix_sort_longs(std::ptr::null_mut(), 5);
        let mut one = vec![42u64];
        sbs_radix_sort_longs(one.as_mut_ptr(), 1); // no-op
        assert_eq!(one, vec![42]);
    }

    // ── rayon-parallel entry (branch B) ───────────────────────────────────────────────────────

    #[test]
    fn parallel_matches_sort_across_sizes() {
        let mut seed = 0xDEAD_BEEF_CAFE_F00Du64;
        let next = |s: &mut u64| {
            *s ^= *s << 13;
            *s ^= *s >> 7;
            *s ^= *s << 17;
            *s
        };
        // sizes below and above PAR_THRESHOLD (65_536) exercise both the fallback and parallel paths
        for &n in &[2usize, 1_000, 70_000, 200_000] {
            let mut v: Vec<u64> = (0..n).map(|_| next(&mut seed) % 1_000_000).collect();
            let mut want = v.clone();
            want.sort();
            sort_flat_parallel(&mut v, n);
            assert_eq!(v, want, "n={n}");
        }
    }

    #[test]
    fn parallel_c_abi_and_high_magnitude() {
        sbs_radix_sort_longs_par(std::ptr::null_mut(), 0);
        // narrow high-magnitude band, above the parallel threshold
        let base = 1_000_000_000u64;
        let mut v: Vec<u64> =
            (0..80_000u64).map(|i| base + (i.wrapping_mul(2_654_435_761) % 4096)).collect();
        let mut want = v.clone();
        want.sort();
        let n = v.len();
        sbs_radix_sort_longs_par(v.as_mut_ptr(), n);
        assert_eq!(v, want);
    }
}
