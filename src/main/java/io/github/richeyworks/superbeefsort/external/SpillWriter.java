package io.github.richeyworks.superbeefsort.external;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes elements of type {@code K} sequentially to a spill file. Elements are readable back in
 * the same order via a corresponding {@link SpillReader}. Close to flush and finish the file.
 */
final class SpillWriter<K> implements Closeable {

    private final SpillSerializer<K> serializer;
    private final DataOutputStream out;

    SpillWriter(Path path, SpillSerializer<K> serializer) throws IOException {
        this.serializer = serializer;
        this.out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)));
    }

    void write(K value) throws IOException {
        serializer.write(value, out);
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
