package io.github.richeyworks.superbeefsort.source;

import io.github.richeyworks.superbeefsort.external.SpillSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The {@link RecordSource} trio: CSV quoting matrix, JSONL happy/limit cases, and a binary
 * round-trip through {@link BinarySink} → {@link BinarySource}. All file I/O via {@code @TempDir}.
 */
class RecordSourceTest {

    // ── CSV: the quoted-field scanner (unit) ────────────────────────────────────────────────

    @Test
    void csvScannerSplitsPlainFields() {
        assertEquals(List.of("a", "b", "c"), CsvSource.parseLine("a,b,c"));
    }

    @Test
    void csvScannerKeepsQuotedCommaInOneField() {
        assertEquals(List.of("a,b", "c"), CsvSource.parseLine("\"a,b\",c"));
    }

    @Test
    void csvScannerUnescapesDoubledQuotes() {
        // "he said ""hi""" → he said "hi"
        assertEquals(List.of("he said \"hi\"", "x"), CsvSource.parseLine("\"he said \"\"hi\"\"\",x"));
    }

    @Test
    void csvScannerHandlesEmptyFields() {
        assertEquals(List.of("", "", ""), CsvSource.parseLine(",,"));
        assertEquals(List.of("a", ""), CsvSource.parseLine("a,"));
        assertEquals(List.of("", "x"), CsvSource.parseLine("\"\",x"));
    }

    // ── CSV: end-to-end source ──────────────────────────────────────────────────────────────

    @Test
    void csvSourceReadsKeyValueColumns(@TempDir Path dir) throws IOException {
        Path csv = dir.resolve("data.csv");
        Files.write(csv, List.of(
                "id,city,pop",
                "3,\"Springfield, IL\",116",
                "1,Portland,652",
                "2,Ada,1600"), StandardCharsets.UTF_8);

        List<String> keys = new ArrayList<>();
        List<String> vals = new ArrayList<>();
        try (CsvSource<Integer, String> src =
                     CsvSource.of(csv, 0, 1, true, Integer::parseInt, s -> s)) {
            RecordSource.Record<Integer, String> r;
            while ((r = src.next()) != null) {
                keys.add(String.valueOf(r.key()));
                vals.add(r.value());
            }
        }
        assertEquals(List.of("3", "1", "2"), keys);
        assertEquals(List.of("Springfield, IL", "Portland", "Ada"), vals);   // quoted comma preserved
    }

    @Test
    void csvSourceSkipsBlankLinesButFailsOnTooFewColumns(@TempDir Path dir) throws IOException {
        Path csv = dir.resolve("ragged.csv");
        Files.write(csv, List.of("k,v", "", "onlyonefield"), StandardCharsets.UTF_8);
        try (CsvSource<String, String> src = CsvSource.of(csv, 0, 1, true, s -> s, s -> s)) {
            assertThrows(IOException.class, src::next);       // row has no column index 1
        }
    }

    // ── JSONL ───────────────────────────────────────────────────────────────────────────────

    @Test
    void jsonlSourceExtractsFields(@TempDir Path dir) throws IOException {
        Path jsonl = dir.resolve("data.jsonl");
        Files.write(jsonl, List.of(
                "{\"id\": 5, \"name\": \"alice\"}",
                "",
                "{\"name\":\"bob\",\"id\":9}",
                "{\"id\": 12, \"name\": \"carol\"}"), StandardCharsets.UTF_8);

        List<Integer> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        try (JsonlSource<Integer, String> src =
                     JsonlSource.of(jsonl, "id", "name", Integer::parseInt, s -> s)) {
            RecordSource.Record<Integer, String> r;
            while ((r = src.next()) != null) {
                ids.add(r.key());
                names.add(r.value());
            }
        }
        assertEquals(List.of(5, 9, 12), ids);
        assertEquals(List.of("alice", "bob", "carol"), names);
    }

    @Test
    void jsonlSourceIsNotFooledByFieldNameInsideAStringValue(@TempDir Path dir) throws IOException {
        Path jsonl = dir.resolve("tricky.jsonl");
        // "id" appears inside the note's string value; the real id is 42.
        Files.write(jsonl, List.of("{\"note\":\"id: unknown\",\"id\":42,\"v\":7}"),
                StandardCharsets.UTF_8);
        try (JsonlSource<Integer, Integer> src =
                     JsonlSource.of(jsonl, "id", "v", Integer::parseInt, Integer::parseInt)) {
            RecordSource.Record<Integer, Integer> r = src.next();
            assertEquals(42, r.key());
            assertEquals(7, r.value());
        }
    }

    @Test
    void jsonlSourceFailsLoudlyOnMissingField(@TempDir Path dir) throws IOException {
        Path jsonl = dir.resolve("missing.jsonl");
        Files.write(jsonl, List.of("{\"id\": 5}"), StandardCharsets.UTF_8);
        try (JsonlSource<Integer, String> src =
                     JsonlSource.of(jsonl, "id", "name", Integer::parseInt, s -> s)) {
            assertThrows(IOException.class, src::next);       // value field 'name' absent
        }
    }

    // ── Binary round-trip ───────────────────────────────────────────────────────────────────

    @Test
    void binarySinkAndSourceRoundTrip(@TempDir Path dir) throws IOException {
        Path bin = dir.resolve("data.bin");
        List<Integer> keys = List.of(7, -3, 0, 42, Integer.MAX_VALUE, Integer.MIN_VALUE);
        List<String> vals = List.of("seven", "neg", "zero", "answer", "max", "min");
        try (BinarySink<Integer, String> sink =
                     BinarySink.create(bin, SpillSerializer.forIntegers(), SpillSerializer.forStrings())) {
            for (int i = 0; i < keys.size(); i++) {
                sink.write(keys.get(i), vals.get(i));
            }
        }

        List<Integer> readKeys = new ArrayList<>();
        List<String> readVals = new ArrayList<>();
        try (BinarySource<Integer, String> src =
                     BinarySource.of(bin, SpillSerializer.forIntegers(), SpillSerializer.forStrings())) {
            RecordSource.Record<Integer, String> r;
            while ((r = src.next()) != null) {
                readKeys.add(r.key());
                readVals.add(r.value());
            }
        }
        assertEquals(keys, readKeys);
        assertEquals(vals, readVals);
    }

    @Test
    void binarySourceOnEmptyFileYieldsNothing(@TempDir Path dir) throws IOException {
        Path bin = dir.resolve("empty.bin");
        try (BinarySink<Long, Long> sink =
                     BinarySink.create(bin, SpillSerializer.forLongs(), SpillSerializer.forLongs())) {
            // write nothing
        }
        try (BinarySource<Long, Long> src =
                     BinarySource.of(bin, SpillSerializer.forLongs(), SpillSerializer.forLongs())) {
            assertNull(src.next());
        }
    }
}
