package io.github.richeyworks.superbeefsort.strategy;

import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.core.SortStrategy;
import io.github.richeyworks.superbeefsort.core.StrategyCapabilities;
import io.github.richeyworks.superbeefsort.core.StrategyId;

import java.util.ArrayList;
import java.util.List;

/** Stable top-down merge sort with an auxiliary buffer. Predictable O(n log n), order-preserving. */
public final class MergeSortStrategy<K> implements SortStrategy<K> {

    public static final StrategyId ID = StrategyId.of("merge");

    @Override
    public void sort(SortBuffer<K> b, SortContext context) {
        int n = b.size();
        if (n < 2) {
            return;
        }
        List<K> work = b.toList();
        List<K> aux = new ArrayList<>(work); // same size; scratch space
        mergeSort(b, work, aux, 0, n);
        for (int i = 0; i < n; i++) {
            b.set(i, work.get(i));
        }
    }

    private void mergeSort(SortBuffer<K> b, List<K> a, List<K> aux, int lo, int hi) {
        if (hi - lo < 2) {
            return;
        }
        int mid = (lo + hi) >>> 1;
        mergeSort(b, a, aux, lo, mid);
        mergeSort(b, a, aux, mid, hi);

        int i = lo, j = mid, k = lo;
        while (i < mid && j < hi) {
            // Strict "<" keeps the sort stable: ties take the left (earlier) element first.
            if (b.compareValues(a.get(j), a.get(i)) < 0) {
                aux.set(k++, a.get(j++));
            } else {
                aux.set(k++, a.get(i++));
            }
            b.recordMove();
        }
        while (i < mid) {
            aux.set(k++, a.get(i++));
            b.recordMove();
        }
        while (j < hi) {
            aux.set(k++, a.get(j++));
            b.recordMove();
        }
        for (int t = lo; t < hi; t++) {
            a.set(t, aux.get(t));
        }
    }

    @Override
    public StrategyCapabilities capabilities() {
        return StrategyCapabilities.builder().stable(true).inPlace(false)
                .auxMemory(StrategyCapabilities.AuxMemory.LINEAR).build();   // O(n) merge scratch
    }

    @Override
    public StrategyId id() {
        return ID;
    }
}
