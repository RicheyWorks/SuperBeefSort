//! SuperBeefSort Phase 2 kernel (proof-of-concept): an LSD radix sort over u64 keys, 8 bits/pass,
//! exposed via the C ABI so the JVM can call it through Panama FFM on an off-heap MemorySegment.
//! Sorts in place, ascending. Signed Java longs are mapped to this unsigned order by the caller
//! (key ^ Long.MIN_VALUE), exactly as the pure-Java RadixSortStrategy does.
//!
//! Build:  rustc --crate-type=cdylib -O radix.rs -o libsbsradix.so
//! Test:   rustc --test -O radix.rs -o radixtest && ./radixtest

fn radix_u64(a: &mut [u64]) {
    let n = a.len();
    if n < 2 { return; }
    let mut max = 0u64;
    for &x in a.iter() { if x > max { max = x; } }
    let mut passes = 0;
    { let mut m = max; while m > 0 { passes += 1; m >>= 8; } }
    if passes == 0 { passes = 1; }

    let mut from = a.to_vec();
    let mut to = vec![0u64; n];
    for pass in 0..passes {
        let shift = pass * 8;
        let mut count = [0usize; 257];
        for &x in from.iter() { count[(((x >> shift) & 0xff) as usize) + 1] += 1; }
        for i in 0..256 { count[i + 1] += count[i]; }
        for &x in from.iter() {
            let d = ((x >> shift) & 0xff) as usize;
            to[count[d]] = x;
            count[d] += 1;
        }
        std::mem::swap(&mut from, &mut to);
    }
    a.copy_from_slice(&from);
}

/// C ABI entry point: sort `len` u64 values at `ptr` in place, ascending. No-op for null / len < 2.
#[no_mangle]
pub extern "C" fn sbs_radix_sort_u64(ptr: *mut u64, len: usize) {
    if ptr.is_null() || len < 2 { return; }
    let a = unsafe { std::slice::from_raw_parts_mut(ptr, len) };
    radix_u64(a);
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn sorts_like_reference() {
        let mut seed: u64 = 0x9E37_79B9_7F4A_7C15;
        for trial in 0..200 {
            let n = (trial * 7) % 500;
            let mut v: Vec<u64> = (0..n)
                .map(|_| { seed ^= seed << 13; seed ^= seed >> 7; seed ^= seed << 17; seed % 100_000 })
                .collect();
            let mut want = v.clone();
            want.sort();
            radix_u64(&mut v);
            assert_eq!(v, want, "n={}", n);
        }
    }

    #[test]
    fn handles_empty_and_single() {
        let mut e: Vec<u64> = vec![];
        radix_u64(&mut e);
        let mut s = vec![42u64];
        radix_u64(&mut s);
        assert_eq!(s, vec![42]);
    }
}
