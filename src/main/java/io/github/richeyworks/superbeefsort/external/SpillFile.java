package io.github.richeyworks.superbeefsort.external;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A temp file that holds one sorted run for the external merge sort. Lifecycle: create via
 * {@link #create}, write elements via {@link #writer()}, read them back in order via
 * {@link #reader()}, then {@link #delete()} when done. The file is registered for
 * deletion-on-JVM-exit so a crash or exception leaves no permanent garbage on disk.
 */
final class SpillFile<K> {

    private final Path path;
    private final SpillSerializer<K> serializer;

    private SpillFile(Path path, SpillSerializer<K> serializer) {
        this.path = path;
        this.serializer = serializer;
    }

    static <K> SpillFile<K> create(SpillSerializer<K> serializer) throws IOException {
        Path tmp = Files.createTempFile("sbs-spill-", ".bin");
        tmp.toFile().deleteOnExit();
        return new SpillFile<>(tmp, serializer);
    }

    SpillWriter<K> writer() throws IOException {
        return new SpillWriter<>(path, serializer);
    }

    SpillReader<K> reader() throws IOException {
        return new SpillReader<>(path, serializer);
    }

    void delete() {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
