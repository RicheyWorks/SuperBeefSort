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
}
