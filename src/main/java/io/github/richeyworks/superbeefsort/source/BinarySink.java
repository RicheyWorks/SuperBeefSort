package io.github.richeyworks.superbeefsort.source;

import io.github.richeyworks.superbeefsort.external.SpillSerializer;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The write side of {@link BinarySource}: appends length-prefixed {@code (key, value)} records,
 * each field framed by its own {@link SpillSerializer}. Provided so binary-source round-trip tests
 * are self-contained (write with the sink, read back with the source) and so callers have a
 * first-class way to materialize a {@link RecordSource} to disk. Close to flush and finish.
 */
public final class BinarySink<K, V> implements Closeable {

    private final DataOutputStream out;
    private final SpillSerializer<K> keySerializer;
    private final SpillSerializer<V> valueSerializer;

    private BinarySink(DataOutputStream out, SpillSerializer<K> keySerializer,
                       SpillSerializer<V> valueSerializer) {
        this.out = out;
        this.keySerializer = keySerializer;
        this.valueSerializer = valueSerializer;
    }

    public static <K, V> BinarySink<K, V> create(Path path, SpillSerializer<K> keySerializer,
                                                 SpillSerializer<V> valueSerializer) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(keySerializer, "keySerializer");
        Objects.requireNonNull(valueSerializer, "valueSerializer");
        return new BinarySink<>(
                new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path))),
                keySerializer, valueSerializer);
    }

    /** Append one record: the key's bytes immediately followed by the value's bytes. */
    public void write(K key, V value) throws IOException {
        keySerializer.write(key, out);
        valueSerializer.write(value, out);
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
