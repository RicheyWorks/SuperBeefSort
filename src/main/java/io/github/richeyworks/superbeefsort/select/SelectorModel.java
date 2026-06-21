package io.github.richeyworks.superbeefsort.select;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A loaded SBS strategy-selection model: the dependency-free flat decision tree exported by
 * {@code tools/phase4/train_selector.py} (schema v1, ADR docs/adr-phase4-python-intelligence.md
 * action item 3). Evaluated in-process by {@link LearnedModelStrategySelector} — no JSON parser, no
 * ML runtime, no network. The parallel-array layout is sklearn's {@code tree_} structure flattened.
 *
 * <p><b>File format</b> (one record per line; {@code key value...}):</p>
 * <pre>
 *   sbs-selector-model &lt;version&gt; &lt;model_type&gt;
 *   features   &lt;comma-separated feature column names, in evaluation order&gt;
 *   classes    &lt;comma-separated strategy ids&gt;
 *   n_nodes    &lt;count&gt;
 *   feature    &lt;space-separated ints; index into features, -1 at a leaf&gt;
 *   threshold  &lt;space-separated doubles; go LEFT when feature_value &lt;= threshold&gt;
 *   left       &lt;space-separated ints; child node id, -1 at a leaf&gt;
 *   right      &lt;space-separated ints&gt;
 *   class      &lt;space-separated ints; argmax class index, read at a leaf&gt;
 *   confidence &lt;space-separated doubles; leaf purity, for the selector's confidence gate&gt;
 * </pre>
 *
 * <p>The {@link #featureColumns()} order is the contract the caller must honour when building the
 * feature vector for {@link #predict}. Loading is fail-fast (unsupported version or ragged arrays
 * throw); {@link #fromClasspath} returns {@link Optional#empty()} when no model resource is present,
 * so a selector can degrade to its delegate — the engine's usual capability-fallback discipline.</p>
 */
public final class SelectorModel {

    /** The only schema this loader accepts. */
    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    private final List<String> featureColumns;
    private final List<String> classes;
    private final int[] feature;       // index into featureColumns; -1 at a leaf
    private final double[] threshold;  // go LEFT when value <= threshold
    private final int[] left;
    private final int[] right;
    private final int[] clazz;         // argmax class index (read at a leaf)
    private final double[] confidence; // leaf purity

    /** One model verdict: the predicted strategy id and the leaf's confidence (purity) in {@code [0,1]}. */
    public record Prediction(String label, double confidence) {
    }

    private SelectorModel(List<String> featureColumns, List<String> classes, int[] feature,
                          double[] threshold, int[] left, int[] right, int[] clazz, double[] confidence) {
        this.featureColumns = List.copyOf(featureColumns);
        this.classes = List.copyOf(classes);
        this.feature = feature;
        this.threshold = threshold;
        this.left = left;
        this.right = right;
        this.clazz = clazz;
        this.confidence = confidence;
    }

    /** The feature column names, in the order {@link #predict}'s argument must follow. */
    public List<String> featureColumns() {
        return featureColumns;
    }

    /** The strategy ids the model can emit. */
    public List<String> classes() {
        return classes;
    }

    public int nodeCount() {
        return feature.length;
    }

    /**
     * Walk the decision tree to a leaf and return its class + confidence. {@code featureValues} must
     * be in {@link #featureColumns()} order (use {@link #featureColumns()} to build it).
     */
    public Prediction predict(double[] featureValues) {
        int node = 0;
        while (feature[node] >= 0) {
            node = featureValues[feature[node]] <= threshold[node] ? left[node] : right[node];
        }
        return new Prediction(classes.get(clazz[node]), confidence[node]);
    }

    // ---- loading ---- //

    /** Load from a {@link Reader}; the caller owns the reader's lifecycle. */
    public static SelectorModel load(Reader reader) {
        Map<String, String> kv = new HashMap<>();
        int version = -1;
        try (BufferedReader br = new BufferedReader(reader)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.startsWith("sbs-selector-model")) {
                    String[] h = line.split("\\s+");
                    if (h.length < 3) {
                        throw new IllegalArgumentException("malformed selector-model header: " + line);
                    }
                    version = Integer.parseInt(h[1]);
                    continue;
                }
                int sp = line.indexOf(' ');
                if (sp < 0) {
                    throw new IllegalArgumentException("malformed selector-model line: " + line);
                }
                kv.put(line.substring(0, sp), line.substring(sp + 1).strip());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("reading selector model", e);
        }
        if (version != SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported selector-model schema version " + version
                            + " (this build supports " + SUPPORTED_SCHEMA_VERSION + ")");
        }
        List<String> features = splitCsv(require(kv, "features"));
        List<String> classes = splitCsv(require(kv, "classes"));
        int[] feature = ints(require(kv, "feature"));
        double[] threshold = doubles(require(kv, "threshold"));
        int[] left = ints(require(kv, "left"));
        int[] right = ints(require(kv, "right"));
        int[] clazz = ints(require(kv, "class"));
        double[] confidence = doubles(require(kv, "confidence"));

        int n = feature.length;
        if (threshold.length != n || left.length != n || right.length != n
                || clazz.length != n || confidence.length != n) {
            throw new IllegalArgumentException("ragged node arrays in selector model (n_nodes mismatch)");
        }
        if (n == 0) {
            throw new IllegalArgumentException("selector model has no nodes");
        }
        return new SelectorModel(features, classes, feature, threshold, left, right, clazz, confidence);
    }

    /** Load from a file path. */
    public static SelectorModel load(Path path) {
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return load(r);
        } catch (IOException e) {
            throw new UncheckedIOException("reading selector model " + path, e);
        }
    }

    /** Parse from an in-memory string (handy for tests and embedding). */
    public static SelectorModel parse(String text) {
        return load(new StringReader(text));
    }

    /**
     * Load from a classpath resource, or {@link Optional#empty()} when it is absent — letting a
     * selector fall back to its delegate when no model ships.
     */
    public static Optional<SelectorModel> fromClasspath(String resource) {
        InputStream in = SelectorModel.class.getResourceAsStream(resource);
        if (in == null) {
            return Optional.empty();
        }
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return Optional.of(load(r));
        } catch (IOException e) {
            throw new UncheckedIOException("reading selector model resource " + resource, e);
        }
    }

    // ---- parsing helpers ---- //

    private static String require(Map<String, String> kv, String key) {
        String v = kv.get(key);
        if (v == null) {
            throw new IllegalArgumentException("selector model missing required line: " + key);
        }
        return v;
    }

    private static List<String> splitCsv(String s) {
        List<String> out = new ArrayList<>();
        for (String tok : s.split(",")) {
            String t = tok.strip();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static int[] ints(String s) {
        String[] toks = s.split("\\s+");
        int[] out = new int[toks.length];
        for (int i = 0; i < toks.length; i++) {
            out[i] = Integer.parseInt(toks[i]);
        }
        return out;
    }

    private static double[] doubles(String s) {
        String[] toks = s.split("\\s+");
        double[] out = new double[toks.length];
        for (int i = 0; i < toks.length; i++) {
            out[i] = Double.parseDouble(toks[i]);
        }
        return out;
    }
}
