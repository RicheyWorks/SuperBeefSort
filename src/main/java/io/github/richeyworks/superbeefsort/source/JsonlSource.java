package io.github.richeyworks.superbeefsort.source;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;

/**
 * A {@link RecordSource} over a JSON Lines file — one flat JSON object per line, key and value
 * pulled from two named top-level fields via {@link MiniJson}. Streaming and single-pass.
 *
 * <p><b>Documented limits (v1, loudly — the zero-dependency tradition, ground rule 6):</b> this is
 * the deliberately minimal top-level scanner of {@link MiniJson}, not a real JSON parser. It
 * extracts a string or number token for a field at nesting depth 1; it does <em>not</em> read
 * nested objects/arrays as values, and handles only standard escapes. One object per line; blank
 * lines are skipped. A line missing the key or value field is a hard error ({@link IOException}).
 * Anything beyond this is a real JSON-dependency decision for a future ADR — not a silent extension
 * of the scanner.
 */
public final class JsonlSource<K, V> implements RecordSource<K, V> {

    private final BufferedReader reader;
    private final String keyField;
    private final String valueField;
    private final Function<String, K> keyParser;
    private final Function<String, V> valueParser;
    private long lineNumber;

    private JsonlSource(BufferedReader reader, String keyField, String valueField,
                        Function<String, K> keyParser, Function<String, V> valueParser) {
        this.reader = reader;
        this.keyField = keyField;
        this.valueField = valueField;
        this.keyParser = keyParser;
        this.valueParser = valueParser;
    }

    /**
     * Open a JSONL source over {@code path} (UTF-8).
     *
     * @param keyField    the top-level field name the key is extracted from
     * @param valueField  the top-level field name the value is extracted from
     * @param keyParser   maps the raw key token to {@code K} (e.g. {@code Integer::parseInt})
     * @param valueParser maps the raw value token to {@code V}
     */
    public static <K, V> JsonlSource<K, V> of(Path path, String keyField, String valueField,
                                              Function<String, K> keyParser,
                                              Function<String, V> valueParser) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(keyField, "keyField");
        Objects.requireNonNull(valueField, "valueField");
        Objects.requireNonNull(keyParser, "keyParser");
        Objects.requireNonNull(valueParser, "valueParser");
        return new JsonlSource<>(Files.newBufferedReader(path, StandardCharsets.UTF_8),
                keyField, valueField, keyParser, valueParser);
    }

    @Override
    public Record<K, V> next() throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            String rawKey = MiniJson.field(line, keyField);
            if (rawKey == null) {
                throw new IOException("JSONL line " + lineNumber + " missing key field '"
                        + keyField + "': " + line);
            }
            String rawValue = MiniJson.field(line, valueField);
            if (rawValue == null) {
                throw new IOException("JSONL line " + lineNumber + " missing value field '"
                        + valueField + "': " + line);
            }
            return new Record<>(keyParser.apply(rawKey), valueParser.apply(rawValue));
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
