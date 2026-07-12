package io.github.richeyworks.superbeefsort.source;

import java.io.IOException;

/**
 * A single-pass, streaming, order-preserving source of key/value records — the seam through
 * which the ecosystem eats the outside world (ADR Phase 3). Implementations never materialize
 * the whole input: {@code next()} until {@code null}, then {@code close()}. Consumers include
 * the iterator-based external sort (datasets bigger than memory) and SmokeHouse's
 * {@code importInto} (ingestion as recovery).
 */
public interface RecordSource<K, V> extends AutoCloseable {

    /** The next record in source order, or {@code null} at the end. */
    Record<K, V> next() throws IOException;

    /** One key/value pair. */
    record Record<K, V>(K key, V value) { }

    @Override
    void close() throws IOException;
}
