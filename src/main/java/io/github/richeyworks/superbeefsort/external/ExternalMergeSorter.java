package io.github.richeyworks.superbeefsort.external;

import io.github.richeyworks.superbeefsort.engine.BeefSortEngine;
import io.github.richeyworks.superbeefsort.engine.JobSpec;
import io.github.richeyworks.superbeefsort.feed.CsrbtTarget;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * External merge sort: sorts inputs that exceed what fits in a single in-memory {@code SortBuffer}.
 *
 * <ol>
 *   <li><b>Run generation</b> — splits {@code input} into chunks of {@code runSize} elements,
 *       sorts each chunk with the full in-memory engine (profile → select → sort), and spills the
 *       sorted chunk to a temp file. Every chunk benefits from profiling and intelligent selection:
 *       a nearly-sorted chunk still gets TimSort, an integer chunk still gets radix, etc.</li>
 *   <li><b>Multi-pass merge</b> — while the run count exceeds {@code maxFanIn}, repeatedly merges
 *       groups of {@code maxFanIn} runs into intermediate temp files until ≤ {@code maxFanIn}
 *       remain.</li>
 *   <li><b>Final merge</b> — a {@link TournamentTree} k-way-merges the remaining runs into the
 *       output. Equal elements are ordered by run index (earlier chunk first), making the overall
 *       sort stable when each chunk sort is stable (use {@code SelectionPolicy.STABLE}).</li>
 * </ol>
 *
 * <p>Temp files are registered for deletion-on-JVM-exit and are explicitly deleted as soon as they
 * are no longer needed. Access via {@link io.github.richeyworks.superbeefsort.BeefSort#external}.</p>
 */
public final class ExternalMergeSorter<K> {

    private final BeefSortEngine<K> engine;
    private final Comparator<? super K> comparator;
    private final SpillSerializer<K> serializer;
    private final int runSize;
    private final int maxFanIn;
    private final JobSpec jobSpec;

    public ExternalMergeSorter(BeefSortEngine<K> engine, Comparator<? super K> comparator,
                        SpillSerializer<K> serializer, int runSize, int maxFanIn, JobSpec jobSpec) {
        this.engine = engine;
        this.comparator = comparator;
        this.serializer = serializer;
        this.runSize = runSize;
        this.maxFanIn = maxFanIn;
        this.jobSpec = jobSpec;
    }

    /**
     * Sort {@code input} and return the merged result as an ordered list. Materialises the full
     * output in memory; use for testing or small inputs. For truly large inputs, prefer
     * {@link #sortAndFeed}.
     */
    public List<K> sortToList(List<K> input) throws IOException {
        List<SpillFile<K>> runs = generateRuns(input);
        List<SpillFile<K>> finalRuns = mergePasses(runs);
        List<SpillReader<K>> readers = openReaders(finalRuns);
        TournamentTree<K> tree = new TournamentTree<>(readers, comparator);
        List<K> result = new ArrayList<>(input.size());
        try {
            while (tree.hasNext()) {
                result.add(tree.next());
            }
        } finally {
            tree.closeAll();
            for (SpillFile<K> f : finalRuns) {
                f.delete();
            }
        }
        return result;
    }

    /**
     * Sort {@code input} and stream-feed the merged output directly into {@code target} without
     * materialising the full output in memory — the out-of-core path. When {@code maxSize > 0}
     * and the target supports it, sets the bounded-window capacity before feeding.
     */
    public ExternalSortResult sortAndFeed(List<K> input, CsrbtTarget<K> target, int maxSize) throws IOException {
        long t0 = System.nanoTime();
        int n = input.size();
        List<SpillFile<K>> runs = generateRuns(input);
        int numRuns = runs.size();
        List<SpillFile<K>> finalRuns = mergePasses(runs);
        int passes = countPasses(numRuns);

        List<SpillReader<K>> readers = openReaders(finalRuns);
        TournamentTree<K> tree = new TournamentTree<>(readers, comparator);
        try {
            if (maxSize > 0 && target.supportsWindow()) {
                target.setMaxSize(maxSize);
            }
            while (tree.hasNext()) {
                target.add(tree.next());
            }
        } finally {
            tree.closeAll();
            for (SpillFile<K> f : finalRuns) {
                f.delete();
            }
        }
        return new ExternalSortResult(n, numRuns, passes, System.nanoTime() - t0);
    }

    // ---- run generation ----

    private List<SpillFile<K>> generateRuns(List<K> input) throws IOException {
        List<SpillFile<K>> spills = new ArrayList<>();
        for (int from = 0; from < input.size(); from += runSize) {
            int to = Math.min(input.size(), from + runSize);
            List<K> sorted = engine.sort(input.subList(from, to), comparator, jobSpec).sorted();
            SpillFile<K> spill = SpillFile.create(serializer);
            spills.add(spill);
            try (SpillWriter<K> w = spill.writer()) {
                for (K element : sorted) {
                    w.write(element);
                }
            }
        }
        return spills;
    }

    // ---- multi-pass merge ----

    /**
     * Reduces the run list to ≤ {@code maxFanIn} files by repeatedly merging groups. Intermediate
     * temp files from each pass are deleted once the next pass no longer needs them.
     */
    private List<SpillFile<K>> mergePasses(List<SpillFile<K>> runs) throws IOException {
        List<SpillFile<K>> current = runs;
        while (current.size() > maxFanIn) {
            List<SpillFile<K>> next = new ArrayList<>();
            for (int i = 0; i < current.size(); i += maxFanIn) {
                int end = Math.min(current.size(), i + maxFanIn);
                next.add(mergeGroup(current.subList(i, end)));
            }
            for (SpillFile<K> f : current) {
                f.delete();
            }
            current = next;
        }
        return current;
    }

    /** Merge a group of run files into one new spill file. Closes all readers when done. */
    private SpillFile<K> mergeGroup(List<SpillFile<K>> group) throws IOException {
        SpillFile<K> merged = SpillFile.create(serializer);
        List<SpillReader<K>> readers = openReaders(group);
        try (SpillWriter<K> w = merged.writer()) {
            TournamentTree<K> tree = new TournamentTree<>(readers, comparator);
            while (tree.hasNext()) {
                w.write(tree.next());
            }
        } finally {
            closeReaders(readers);
        }
        return merged;
    }

    // ---- helpers ----

    private List<SpillReader<K>> openReaders(List<SpillFile<K>> files) throws IOException {
        List<SpillReader<K>> readers = new ArrayList<>(files.size());
        for (SpillFile<K> f : files) {
            readers.add(f.reader());
        }
        return readers;
    }

    private void closeReaders(List<SpillReader<K>> readers) {
        for (SpillReader<K> r : readers) {
            try {
                r.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Number of merge passes (including the final one): 1 if all runs fit in one pass, more otherwise. */
    private int countPasses(int numRuns) {
        if (numRuns <= 1) return 1;
        int passes = 1;
        int current = numRuns;
        while (current > maxFanIn) {
            current = (current + maxFanIn - 1) / maxFanIn;
            passes++;
        }
        return passes;
    }
}
