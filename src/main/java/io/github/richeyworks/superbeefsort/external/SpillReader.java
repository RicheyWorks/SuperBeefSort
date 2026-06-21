package io.github.richeyworks.superbeefsort.external;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads elements written by a {@link SpillWriter} back in order. Iteration uses a peek-ahead
 * model: {@link #hasNext()} returns false when the stream is exhausted. EOFException signals the
 * end of the file; any other IOException is rethrown as an {@link UncheckedIOException}.
 */
final class SpillReader<K> implements Closeable {

    private final SpillSerializer<K> serializer;
    private final DataInputStream in;
    private K buffered;
    private boolean done;

    SpillReader(Path path, SpillSerializer<K> serializer) throws IOException {
        this.serializer = serializer;
        this.in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)));
        advance();
    }

    boolean hasNext() {
        return !done;
    }

    K next() {
        K val = buffered;
        advance();
        return val;
    }

    private void advance() {
        try {
            buffered = serializer.read(in);
        } catch (EOFException e) {
            done = true;
            buffered = null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
