package io.github.richeyworks.superbeefsort.source;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * A {@link RecordSource} over a CSV file — one record per line, key and value pulled from two
 * named columns. Streaming and single-pass: {@link #next()} reads one line at a time and never
 * materializes the file.
 *
 * <p><b>Field scanner (RFC-4180 subset):</b> fields split on commas; a field may be wrapped in
 * double quotes, inside which a comma is literal and a doubled quote ({@code ""}) is an escaped
 * quote. So {@code "a,b",c} is two fields ({@code a,b} and {@code c}) and {@code "he said ""hi"""}
 * is one field ({@code he said "hi"}).
 *
 * <p><b>Documented limits (v1, deliberately minimal — the zero-dependency tradition):</b> no
 * multiline quoted fields (a record is exactly one physical line), and the delimiter is a literal
 * comma (no custom delimiters). Fully-empty lines are skipped. A row with fewer columns than the
 * requested key/value index is a hard error ({@link IOException}) — malformed data fails loudly
 * rather than silently dropping records. If a workload needs more, that is a real CSV-parser
 * dependency decision for a future ADR, not a silent extension of this scanner.
 */
public final class CsvSource<K, V> implements RecordSource<K, V> {

    private final BufferedReader reader;
    private final int keyColumn;
    private final int valueColumn;
    private final Function<String, K> keyParser;
    private final Function<String, V> valueParser;
    private boolean headerPending;
    private long lineNumber;

    private CsvSource(BufferedReader reader, int keyColumn, int valueColumn, boolean skipHeader,
                      Function<String, K> keyParser, Function<String, V> valueParser) {
        this.reader = reader;
        this.keyColumn = keyColumn;
        this.valueColumn = valueColumn;
        this.keyParser = keyParser;
        this.valueParser = valueParser;
        this.headerPending = skipHeader;
    }

    /**
     * Open a CSV source over {@code path} (UTF-8).
     *
     * @param path        the CSV file
     * @param keyColumn   zero-based column index the key is parsed from
     * @param valueColumn zero-based column index the value is parsed from
     * @param skipHeader  when {@code true}, the first physical line is consumed and ignored
     * @param keyParser   maps a raw key field to {@code K} (e.g. {@code Integer::parseInt})
     * @param valueParser maps a raw value field to {@code V}
     */
    public static <K, V> CsvSource<K, V> of(Path path, int keyColumn, int valueColumn,
                                            boolean skipHeader, Function<String, K> keyParser,
                                            Function<String, V> valueParser) throws IOException {
        Objects.requireNonNull(path, "path");
        if (keyColumn < 0 || valueColumn < 0) {
            throw new IllegalArgumentException(
                    "column indices must be >= 0: key=" + keyColumn + " value=" + valueColumn);
        }
        Objects.requireNonNull(keyParser, "keyParser");
        Objects.requireNonNull(valueParser, "valueParser");
        return new CsvSource<>(Files.newBufferedReader(path, StandardCharsets.UTF_8),
                keyColumn, valueColumn, skipHeader, keyParser, valueParser);
    }

    @Override
    public Record<K, V> next() throws IOException {
        if (headerPending) {
            reader.readLine();
            headerPending = false;
            lineNumber++;
        }
        String line;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.isEmpty()) {
                continue;                     // tolerate blank lines (e.g. a trailing newline)
            }
            List<String> fields = parseLine(line);
            int need = Math.max(keyColumn, valueColumn);
            if (fields.size() <= need) {
                throw new IOException("CSV line " + lineNumber + " has " + fields.size()
                        + " field(s); need column index " + need + ": " + line);
            }
            return new Record<>(keyParser.apply(fields.get(keyColumn)),
                    valueParser.apply(fields.get(valueColumn)));
        }
        return null;
    }

    /**
     * Split one CSV line into fields using the quoted-field scanner described in the class javadoc.
     * Package-private so {@code RecordSourceTest} can exercise the quoting matrix directly.
     */
    static List<String> parseLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');      // doubled quote → one literal quote
                        i++;
                    } else {
                        inQuotes = false;     // closing quote
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
