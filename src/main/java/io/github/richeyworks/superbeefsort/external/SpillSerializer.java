package io.github.richeyworks.superbeefsort.external;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Binary serialization contract for elements spilled to disk during an external merge sort.
 * Built-in serializers cover the common numeric and string types; supply a custom one for other
 * element types. Implementations must be consistent: {@code write} followed immediately by
 * {@code read} on the same stream must reproduce the original value.
 *
 * @see SpillSerializer#forLongs()
 * @see SpillSerializer#forIntegers()
 * @see SpillSerializer#forStrings()
 */
public interface SpillSerializer<K> {

    void write(K value, DataOutputStream out) throws IOException;

    K read(DataInputStream in) throws IOException;

    static SpillSerializer<Long> forLongs() {
        return new SpillSerializer<>() {
            @Override public void write(Long v, DataOutputStream out) throws IOException { out.writeLong(v); }
            @Override public Long read(DataInputStream in) throws IOException { return in.readLong(); }
        };
    }

    static SpillSerializer<Integer> forIntegers() {
        return new SpillSerializer<>() {
            @Override public void write(Integer v, DataOutputStream out) throws IOException { out.writeInt(v); }
            @Override public Integer read(DataInputStream in) throws IOException { return in.readInt(); }
        };
    }

    /** Uses {@link DataOutputStream#writeUTF} / {@link DataInputStream#readUTF}: valid for strings up to 65535 UTF-8 bytes. */
    static SpillSerializer<String> forStrings() {
        return new SpillSerializer<>() {
            @Override public void write(String v, DataOutputStream out) throws IOException { out.writeUTF(v); }
            @Override public String read(DataInputStream in) throws IOException { return in.readUTF(); }
        };
    }
}
