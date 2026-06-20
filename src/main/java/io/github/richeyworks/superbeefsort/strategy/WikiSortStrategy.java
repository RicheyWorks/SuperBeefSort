package io.github.richeyworks.superbeefsort.strategy;

import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.core.StrategyCapabilities;
import io.github.richeyworks.superbeefsort.core.StrategyId;

/**
 * Stable, O(1)-auxiliary <em>block merge sort</em> (ID {@code merge.wiki}) — the O(n&nbsp;log&nbsp;n)
 * successor to {@link InPlaceMergeSortStrategy} ({@code merge.inplace}, O(n&nbsp;log&sup2;&nbsp;n)). This is
 * a faithful port of BonzaiThePenguin's "WikiSort" (itself based on Kim&nbsp;&amp;&nbsp;Kutzner, "Ratio
 * based stable in-place merging"), run with no external cache so the auxiliary space is strictly O(1).
 *
 * <p>Unlike the previous implementation — whose linear-move fast path engaged only on all-distinct
 * regions and fell back to a rotation merge whenever a duplicate appeared — this handles duplicate keys
 * natively, so near-distinct and duplicate-bearing inputs keep O(n&nbsp;log&nbsp;n) moves instead of
 * degrading to O(n&nbsp;log&sup2;&nbsp;n).</p>
 *
 * <p>Structure (bottom-up, iterative, O(1) call stack):</p>
 * <ol>
 *   <li>Insertion-sort initial runs (the iterator's {@code min_level} = 4, so runs of 4&ndash;8).</li>
 *   <li>For each merge level, pull two internal buffers of &radic;n <em>unique</em> values out of the data
 *       (one to tag the A blocks for stable selection, one as swap scratch for the linear-move merges).
 *       If a region can't yield enough unique values, that merge degrades to the strictly-in-place
 *       rotation merge ({@link #mergeInPlace}) — the low-cardinality fallback.</li>
 *   <li>Break A into &radic;n-sized blocks, tag each block's head against the first buffer, then roll the A
 *       blocks through B: repeatedly drop the minimum-headed A block (ties broken by the tag &rArr; original
 *       order &rArr; stability) and buffer-merge the previous A block with the B values that precede it.</li>
 *   <li>Insertion-sort the (jumbled) scratch buffer and redistribute both buffers back into the array.</li>
 * </ol>
 *
 * <p><b>Stability</b> is guaranteed by the unique-value tag buffer (block selection never sees ties) and by
 * each block seam being a merge of a single A block against B values (so ties always resolve toward A,
 * i.e. the earlier run). Verified against a stable reference sort across distinct, near-distinct,
 * duplicate-heavy, all-equal and pathological shapes. Higher comparison constant than plain merge — see
 * PROGRESS.md / the selector routing — so it is chosen only where stability + O(1) memory matter.</p>
 */
public final class WikiSortStrategy<K> implements SortStrategy<K> {

    public static final StrategyId ID = StrategyId.of("merge.wiki");

    @Override
    public void sort(SortBuffer<K> b, SortContext context) {
        int size = b.size();
        if (size < 4) {
            insertion(b, 0, size);
            return;
        }

        // 1. insertion-sort initial runs of 4..8 (a stable network would do; insertion is stable too).
        Iter it = new Iter(size, 4);
        while (!it.finished()) {
            Rng r = it.nextRange();
            insertion(b, r.start, r.end);
        }
        if (size < 8) {
            return;
        }

        Pull[] pull = {new Pull(), new Pull()};

        while (true) {
            int blockSize = intSqrt(it.length());
            int bufferSize = it.length() / blockSize + 1;
            Rng buffer1 = new Rng(0, 0);
            Rng buffer2 = new Rng(0, 0);
            pull[0].reset();
            pull[1].reset();
            int pullIndex = 0;

            // we need two buffers of √A unique values; if they can't share one A/B subarray, find separately
            int find = bufferSize + bufferSize;
            boolean findSeparately = false;
            if (find > it.length()) {
                find = bufferSize;
                findSeparately = true;
            }

            // 2. find the two internal buffers (the most unique values we can pull from some A or B subarray)
            it.begin();
            while (!it.finished()) {
                Rng A = it.nextRange();
                Rng B = it.nextRange();

                // count unique values at the start of A
                int last = A.start;
                int count = 1;
                int index;
                while (count < find) {
                    index = findLastForward(b, b.get(last), last + 1, A.end, find - count);
                    if (index == A.end) break;
                    last = index;
                    count++;
                }
                index = last;
                if (count >= bufferSize) {
                    pull[pullIndex].rng.set(A.start, B.end);
                    pull[pullIndex].count = count;
                    pull[pullIndex].from = index;
                    pull[pullIndex].to = A.start;
                    pullIndex = 1;
                    if (count == bufferSize + bufferSize) {
                        buffer1.set(A.start, A.start + bufferSize);
                        buffer2.set(A.start + bufferSize, A.start + count);
                        break;
                    } else if (find == bufferSize + bufferSize) {
                        buffer1.set(A.start, A.start + count);
                        find = bufferSize;
                    } else if (findSeparately) {
                        buffer1.set(A.start, A.start + count);
                        findSeparately = false;
                    } else {
                        buffer2.set(A.start, A.start + count);
                        break;
                    }
                } else if (pullIndex == 0 && count > buffer1.length()) {
                    buffer1.set(A.start, A.start + count);
                    pull[pullIndex].rng.set(A.start, B.end);
                    pull[pullIndex].count = count;
                    pull[pullIndex].from = index;
                    pull[pullIndex].to = A.start;
                }

                // count unique values at the end of B
                last = B.end - 1;
                count = 1;
                while (count < find) {
                    index = findFirstBackward(b, b.get(last), B.start, last, find - count);
                    if (index == B.start) break;
                    last = index - 1;
                    count++;
                }
                index = last;
                if (count >= bufferSize) {
                    pull[pullIndex].rng.set(A.start, B.end);
                    pull[pullIndex].count = count;
                    pull[pullIndex].from = index;
                    pull[pullIndex].to = B.end;
                    pullIndex = 1;
                    if (count == bufferSize + bufferSize) {
                        buffer1.set(B.end - count, B.end - bufferSize);
                        buffer2.set(B.end - bufferSize, B.end);
                        break;
                    } else if (find == bufferSize + bufferSize) {
                        buffer1.set(B.end - count, B.end);
                        find = bufferSize;
                    } else if (findSeparately) {
                        buffer1.set(B.end - count, B.end);
                        findSeparately = false;
                    } else {
                        if (pull[0].rng.start == A.start) pull[0].rng.end -= pull[1].count;
                        buffer2.set(B.end - count, B.end);
                        break;
                    }
                } else if (pullIndex == 0 && count > buffer1.length()) {
                    buffer1.set(B.end - count, B.end);
                    pull[pullIndex].rng.set(A.start, B.end);
                    pull[pullIndex].count = count;
                    pull[pullIndex].from = index;
                    pull[pullIndex].to = B.end;
                }
            }

            // pull the two buffers out to the edges of their subarrays via rotations
            for (int pi = 0; pi < 2; pi++) {
                int length = pull[pi].count;
                if (pull[pi].to < pull[pi].from) {
                    int index = pull[pi].from;
                    for (int c = 1; c < length; c++) {
                        index = findFirstBackward(b, b.get(index - 1), pull[pi].to, pull[pi].from - (c - 1), length - c);
                        Rng rng = new Rng(index + 1, pull[pi].from + 1);
                        rotate(b, rng.length() - c, rng.start, rng.end);
                        pull[pi].from = index + c;
                    }
                } else if (pull[pi].to > pull[pi].from) {
                    int index = pull[pi].from + 1;
                    for (int c = 1; c < length; c++) {
                        index = findLastForward(b, b.get(index), index, pull[pi].to, length - c);
                        Rng rng = new Rng(pull[pi].from, index - 1);
                        rotate(b, c, rng.start, rng.end);
                        pull[pi].from = index - 1 - c;
                    }
                }
            }

            // recompute block_size from however many unique values we actually pulled
            bufferSize = buffer1.length();
            blockSize = it.length() / bufferSize + 1;

            // 3. merge each A,B at this level using the buffers (or rotation merge when buffers are absent)
            it.begin();
            while (!it.finished()) {
                Rng A = it.nextRange();
                Rng B = it.nextRange();
                int start = A.start;

                // exclude any buffer values that were pulled out of this subarray
                if (start == pull[0].rng.start) {
                    if (pull[0].from > pull[0].to) {
                        A.start += pull[0].count;
                        if (A.length() == 0) continue;
                    } else if (pull[0].from < pull[0].to) {
                        B.end -= pull[0].count;
                        if (B.length() == 0) continue;
                    }
                }
                if (start == pull[1].rng.start) {
                    if (pull[1].from > pull[1].to) {
                        A.start += pull[1].count;
                        if (A.length() == 0) continue;
                    } else if (pull[1].from < pull[1].to) {
                        B.end -= pull[1].count;
                        if (B.length() == 0) continue;
                    }
                }

                if (b.compare(B.end - 1, A.start) < 0) {
                    // the two ranges are in reverse order — a single rotation fixes it
                    rotate(b, A.length(), A.start, B.end);
                } else if (b.compare(A.end, A.end - 1) < 0) {
                    blockMerge(b, A, B, buffer1, buffer2, blockSize);
                }
            }

            // 4. the scratch buffer is jumbled — sort it, then redistribute both buffers back into the array
            insertion(b, buffer2.start, buffer2.end);
            for (int pi = 0; pi < 2; pi++) {
                int unique = pull[pi].count * 2;
                if (pull[pi].from > pull[pi].to) {
                    Rng buf = new Rng(pull[pi].rng.start, pull[pi].rng.start + pull[pi].count);
                    while (buf.length() > 0) {
                        int index = findFirstForward(b, b.get(buf.start), buf.end, pull[pi].rng.end, unique);
                        int amount = index - buf.end;
                        rotate(b, buf.length(), buf.start, index);
                        buf.start += amount + 1;
                        buf.end += amount;
                        unique -= 2;
                    }
                } else if (pull[pi].from < pull[pi].to) {
                    Rng buf = new Rng(pull[pi].rng.end - pull[pi].count, pull[pi].rng.end);
                    while (buf.length() > 0) {
                        int index = findLastBackward(b, b.get(buf.end - 1), pull[pi].rng.start, buf.start, unique);
                        int amount = buf.start - index;
                        rotate(b, amount, index, buf.end);
                        buf.start -= amount;
                        buf.end -= amount + 1;
                        unique -= 2;
                    }
                }
            }

            if (!it.nextLevel()) break;
        }
    }

    /** Roll A's blocks through B, dropping the minimum-headed block and buffer-merging the previous one. */
    private void blockMerge(SortBuffer<K> b, Rng A, Rng B, Rng buffer1, Rng buffer2, int blockSize) {
        Rng blockA = new Rng(A.start, A.end);
        Rng firstA = new Rng(A.start, A.start + blockA.length() % blockSize);

        // tag: swap each A block's head with a value from buffer1, so block selection tracks original order
        int indexA = buffer1.start;
        for (int idx = firstA.end; idx < blockA.end; idx += blockSize) {
            b.swap(indexA, idx);
            indexA++;
        }

        Rng lastA = new Rng(firstA.start, firstA.end);
        Rng lastB = new Rng(0, 0);
        Rng blockB = new Rng(B.start, B.start + Math.min(blockSize, B.length()));
        blockA.start += firstA.length();
        indexA = buffer1.start;

        if (buffer2.length() > 0) {
            blockSwap(b, lastA.start, buffer2.start, lastA.length());
        }

        if (blockA.length() > 0) {
            while (true) {
                if ((lastB.length() > 0 && b.compare(lastB.end - 1, indexA) >= 0) || blockB.length() == 0) {
                    // drop the minimum A block behind: split the previous B block where this A head belongs
                    int bSplit = binaryFirst(b, b.get(indexA), lastB.start, lastB.end);
                    int bRemaining = lastB.end - bSplit;

                    int minA = blockA.start;
                    for (int findA = minA + blockSize; findA < blockA.end; findA += blockSize) {
                        if (b.compare(findA, minA) < 0) minA = findA;
                    }
                    blockSwap(b, blockA.start, minA, blockSize);

                    // restore the previous A block's head from buffer1, advancing the tag pointer
                    b.swap(blockA.start, indexA);
                    indexA++;

                    // merge the previous A block (lastA) with the B values that follow it
                    if (buffer2.length() > 0) {
                        mergeInternal(b, lastA, new Rng(lastA.end, bSplit), buffer2);
                    } else {
                        mergeInPlace(b, lastA.start, lastA.end, lastA.end, bSplit);
                    }

                    if (buffer2.length() > 0) {
                        // park this A block in buffer2 (where it'll be needed to merge), then slot B over
                        blockSwap(b, blockA.start, buffer2.start, blockSize);
                        blockSwap(b, bSplit, blockA.start + blockSize - bRemaining, bRemaining);
                    } else {
                        rotate(b, blockA.start - bSplit, bSplit, blockA.start + blockSize);
                    }

                    lastA.set(blockA.start - bRemaining, blockA.start - bRemaining + blockSize);
                    lastB.set(lastA.end, lastA.end + bRemaining);

                    blockA.start += blockSize;
                    if (blockA.length() == 0) break;
                } else if (blockB.length() < blockSize) {
                    // the last B block is unevenly sized; rotate it before the remaining A blocks
                    rotate(b, -blockB.length(), blockA.start, blockB.end);
                    lastB.set(blockA.start, blockA.start + blockB.length());
                    blockA.start += blockB.length();
                    blockA.end += blockB.length();
                    blockB.end = blockB.start;
                } else {
                    // roll the leftmost A block to the end by swapping it with the next B block
                    blockSwap(b, blockA.start, blockB.start, blockSize);
                    lastB.set(blockA.start, blockA.start + blockSize);
                    blockA.start += blockSize;
                    blockA.end += blockSize;
                    blockB.start += blockSize;
                    blockB.end += blockSize;
                    if (blockB.end > B.end) blockB.end = B.end;
                }
            }
        }

        // merge the final A block with whatever B values remain
        if (buffer2.length() > 0) {
            mergeInternal(b, lastA, new Rng(lastA.end, B.end), buffer2);
        } else {
            mergeInPlace(b, lastA.start, lastA.end, lastA.end, B.end);
        }
    }

    // ---- merges ----

    /**
     * Buffer-backed merge of A and B, where A's values have already been block-swapped into {@code buf}.
     * Swaps each chosen value into place; {@code buf} is left holding A's original values in a different
     * order. Ties resolve toward A (B&nbsp;&ge;&nbsp;A takes A), so the merge is stable.
     */
    private void mergeInternal(SortBuffer<K> b, Rng A, Rng B, Rng buf) {
        int aCount = 0;
        int bCount = 0;
        int insert = 0;
        if (B.length() > 0 && A.length() > 0) {
            while (true) {
                if (b.compare(B.start + bCount, buf.start + aCount) >= 0) {
                    b.swap(A.start + insert, buf.start + aCount);
                    aCount++;
                    insert++;
                    if (aCount >= A.length()) break;
                } else {
                    b.swap(A.start + insert, B.start + bCount);
                    bCount++;
                    insert++;
                    if (bCount >= B.length()) break;
                }
            }
        }
        blockSwap(b, buf.start + aCount, A.start + insert, A.length() - aCount);
    }

    /** Strictly-in-place stable merge of adjacent {@code [as,ae)} and {@code [bs,be)} via binary-search + rotate. */
    private void mergeInPlace(SortBuffer<K> b, int as, int ae, int bs, int be) {
        if (ae - as == 0 || be - bs == 0) return;
        while (true) {
            int mid = binaryFirst(b, b.get(as), bs, be);
            int amount = mid - ae;
            rotate(b, -amount, as, mid);
            if (be == mid) break;
            bs = mid;
            as = as + amount;
            ae = bs;
            as = binaryLast(b, b.get(as), as, ae);
            if (ae - as == 0) break;
        }
    }

    // ---- searches ----

    /** First index in {@code [lo,hi)} whose element is {@code >= value}. */
    private int binaryFirst(SortBuffer<K> b, K value, int lo, int hi) {
        int start = lo;
        int end = hi - 1;
        while (start < end) {
            int mid = start + (end - start) / 2;
            if (b.compareToKey(mid, value) < 0) start = mid + 1;
            else end = mid;
        }
        if (start == hi - 1 && b.compareToKey(start, value) < 0) start++;
        return start;
    }

    /** First index in {@code [lo,hi)} whose element is {@code > value}. */
    private int binaryLast(SortBuffer<K> b, K value, int lo, int hi) {
        int start = lo;
        int end = hi - 1;
        while (start < end) {
            int mid = start + (end - start) / 2;
            if (b.compareToKey(mid, value) <= 0) start = mid + 1;
            else end = mid;
        }
        if (start == hi - 1 && b.compareToKey(start, value) <= 0) start++;
        return start;
    }

    private int findFirstForward(SortBuffer<K> b, K value, int lo, int hi, int unique) {
        if (hi - lo == 0) return lo;
        int skip = Math.max((hi - lo) / unique, 1);
        int index = lo + skip;
        while (b.compareToKey(index - 1, value) < 0) {
            if (index >= hi - skip) return binaryFirst(b, value, index, hi);
            index += skip;
        }
        return binaryFirst(b, value, index - skip, index);
    }

    private int findLastForward(SortBuffer<K> b, K value, int lo, int hi, int unique) {
        if (hi - lo == 0) return lo;
        int skip = Math.max((hi - lo) / unique, 1);
        int index = lo + skip;
        while (b.compareToKey(index - 1, value) <= 0) {
            if (index >= hi - skip) return binaryLast(b, value, index, hi);
            index += skip;
        }
        return binaryLast(b, value, index - skip, index);
    }

    private int findFirstBackward(SortBuffer<K> b, K value, int lo, int hi, int unique) {
        if (hi - lo == 0) return lo;
        int skip = Math.max((hi - lo) / unique, 1);
        int index = hi - skip;
        while (index > lo && b.compareToKey(index - 1, value) >= 0) {
            if (index < lo + skip) return binaryFirst(b, value, lo, index);
            index -= skip;
        }
        return binaryFirst(b, value, index, index + skip);
    }

    private int findLastBackward(SortBuffer<K> b, K value, int lo, int hi, int unique) {
        if (hi - lo == 0) return lo;
        int skip = Math.max((hi - lo) / unique, 1);
        int index = hi - skip;
        while (index > lo && b.compareToKey(index - 1, value) > 0) {
            if (index < lo + skip) return binaryLast(b, value, lo, index);
            index -= skip;
        }
        return binaryLast(b, value, index, index + skip);
    }

    // ---- primitives ----

    private void insertion(SortBuffer<K> b, int lo, int hi) {
        for (int i = lo + 1; i < hi; i++) {
            K key = b.get(i);
            int j = i - 1;
            while (j >= lo && b.compareToKey(j, key) > 0) {
                b.set(j + 1, b.get(j));
                b.recordMove();
                j--;
            }
            b.set(j + 1, key);
        }
    }

    private void reverse(SortBuffer<K> b, int from, int to) {
        int i = from;
        int j = to - 1;
        while (i < j) {
            b.swap(i, j);
            i++;
            j--;
        }
    }

    private void blockSwap(SortBuffer<K> b, int start1, int start2, int len) {
        for (int k = 0; k < len; k++) {
            b.swap(start1 + k, start2 + k);
        }
    }

    /** Rotate {@code [lo,hi)} by {@code amount} (negative pulls from the right) via three reversals. */
    private void rotate(SortBuffer<K> b, int amount, int lo, int hi) {
        if (hi - lo == 0) return;
        int split = (amount >= 0) ? lo + amount : hi + amount;
        reverse(b, lo, split);
        reverse(b, split, hi);
        reverse(b, lo, hi);
    }

    /** Floor of the integer square root (matches the verified prototype's exact block sizing). */
    private static int intSqrt(int n) {
        if (n < 0) return 0;
        int r = (int) Math.sqrt((double) n);
        while ((long) (r + 1) * (r + 1) <= n) r++;
        while ((long) r * r > n) r--;
        return r;
    }

    // ---- mutable helper structures (all ints — no per-element auxiliary storage) ----

    private static final class Rng {
        int start;
        int end;
        Rng(int s, int e) { start = s; end = e; }
        int length() { return end - start; }
        void set(int s, int e) { start = s; end = e; }
    }

    private static final class Pull {
        int from;
        int to;
        int count;
        final Rng rng = new Rng(0, 0);
        void reset() { from = 0; to = 0; count = 0; rng.set(0, 0); }
    }

    /** Bottom-up merge iterator with fractional decimation so non-power-of-two sizes tile into pairs. */
    private static final class Iter {
        final int size;
        final int denominator;
        int numeratorStep;
        int decimalStep;
        int numerator;
        int decimal;

        Iter(int size, int minLevel) {
            this.size = size;
            int x = size;
            x |= x >> 1; x |= x >> 2; x |= x >> 4; x |= x >> 8; x |= x >> 16;
            int power = x - (x >> 1);
            denominator = power / minLevel;
            numeratorStep = size % denominator;
            decimalStep = size / denominator;
            begin();
        }

        void begin() { numerator = 0; decimal = 0; }

        Rng nextRange() {
            int start = decimal;
            decimal += decimalStep;
            numerator += numeratorStep;
            if (numerator >= denominator) {
                numerator -= denominator;
                decimal++;
            }
            return new Rng(start, decimal);
        }

        boolean finished() { return decimal >= size; }

        boolean nextLevel() {
            decimalStep += decimalStep;
            numeratorStep += numeratorStep;
            if (numeratorStep >= denominator) {
                numeratorStep -= denominator;
                decimalStep++;
            }
            return decimalStep < size;
        }

        int length() { return decimalStep; }
    }

    @Override
    public StrategyCapabilities capabilities() {
        return StrategyCapabilities.builder().stable(true).inPlace(true).comparisonBased(true).build();
    }

    @Override
    public StrategyId id() {
        return ID;
    }
}
