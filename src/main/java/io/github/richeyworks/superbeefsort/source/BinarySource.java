package io.github.richeyworks.superbeefsort.source;

import io.github.richeyworks.superbeefsort.external.SpillSerializer;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A {@link RecordSource} over a length-prefixed binary file written by {@link BinarySink}. Each
 * record is a key immediately followed by a value, each framed by its own
 * {@link SpillSerializer} — the same self-delimiting contract SuperBeefSort's external sort spills
 * with (fixed-width for numbers, {@code writeUTF} length-prefix for strings), so no extra outer
 * framing is needed and no delimiter can collide with the payload.
 *
 * <p>End of stream is a clean EOF at a record boundary (before a key). An EOF <em>within</em> a
 * record — a truncated key or a key with no value — is corruption and surfaces as an
 * {@link IOException}. Pair with {@link BinarySink} for self-contained round-trip tests.
 */
public final class BinarySource<K, V> implements RecordSource<K, V> {

    private final DataInputStream in;
    private final SpillSerializer<K> keySerializer;
    private final SpillSerializer<V> valueSerializer;
    private boolean done;

    private BinarySource(DataInputStream in, SpillSerializer<K> keySerializer,
                         SpillSerializer<V> valueSerializer) {
        this.in = in;
        this.keySerializer = keySerializer;
        this.valueSerializer = valueSerializer;
    }

    public static <K, V> BinarySource<K, V> of(Path path, SpillSerializer<K> keySerializer,
                                               SpillSerializer<V> valueSerializer) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(keySerializer, "keySerializer");
        Objects.requireNonNull(valueSerializer, "valueSerializer");
        return new BinarySource<>(
                new DataInputStream(new BufferedInputStream(Files.newInputStream(path))),
                keySerializer, valueSerializer);
    }

    @Override
    public Record<K, V> next() throws IOException {
        if (done) {
            return null;
        }
        K key;
        try {
            key = keySerializer.read(in);
        } catch (EOFException endOfStream) {
            done = true;                      // clean end: EOF exactly at a record boundary
            return null;
        }
        V value = valueSerializer.read(in);   // EOF here is a torn record → propagates as IOException
        return new Record<>(key, value);
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
