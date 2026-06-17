package io.github.richeyworks.superbeefsort;

import io.github.richeyworks.superbeefsort.core.ByteSequenceEncoder;
import io.github.richeyworks.superbeefsort.core.SortBuffer;
import io.github.richeyworks.superbeefsort.core.SortContext;
import io.github.richeyworks.superbeefsort.engine.BeefSortEngine;
import io.github.richeyworks.superbeefsort.engine.JobSpec;
import io.github.richeyworks.superbeefsort.engine.SortRunResult;
import io.github.richeyworks.superbeefsort.strategy.MsdRadixSortStrategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MSD radix for variable-length keys ({@link MsdRadixSortStrategy} + {@link ByteSequenceEncoder}): it must
 * reproduce the comparator's order (here {@link String}'s natural / UTF-16 order and an unsigned-lex byte[]
 * order) across random and pathological shapes, and be stable. The byte view is faithful to the comparator,
 * so the radix order and the reference sort agree.
 */
class MsdRadixSortTest {

    private static List<String> msd(List<String> input) {
        SortBuffer<String> buffer = SortBuffer.of(input, Comparator.<String>naturalOrder());
        new MsdRadixSortStrategy<>(ByteSequenceEncoder.forStrings()).sort(buffer, SortContext.noop());
        return buffer.toList();
    }

    private static List<String> reference(List<String> input) {
        List<String> e = new ArrayList<>(input);
        e.sort(Comparator.naturalOrder());
        return e;
    }

    private static String randString(Random r, int maxLen, int alphabet) {
        int len = r.nextInt(maxLen + 1);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) ('a' + r.nextInt(alphabet)));
        }
        return sb.toString();
    }

    @Test
    void randomBatteryMatchesReference() {
        for (int seed = 0; seed < 40; seed++) {
            Random r = new Random(seed);
            int n = r.nextInt(400);
            int alphabet = 1 + r.nextInt(6); // tiny alphabets -> many shared prefixes and duplicates
            List<String> input = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                input.add(randString(r, 10, alphabet));
            }
            assertEquals(reference(input), msd(input), "seed " + seed + " (alphabet " + alphabet + ")");
        }
    }

    @Test
    void widerAlphabetAndUnicodeMatchReference() {
        Random r = new Random(99);
        List<String> input = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            int len = r.nextInt(12);
            StringBuilder sb = new StringBuilder(len);
            for (int j = 0; j < len; j++) {
                // mix ASCII and BMP code points (incl. > 0xFF, exercising the high byte of each char)
                sb.append((char) (r.nextInt(2) == 0 ? 32 + r.nextInt(95) : 0x100 + r.nextInt(0x2000)));
            }
            input.add(sb.toString());
        }
        assertEquals(reference(input), msd(input));
    }

    @Test
    void pathologicalShapesMatchReference() {
        List<String> input = List.of(
                "", "a", "ab", "abc", "aa", "a", "b", "abc", "ba", "", "abcd", "abc", "z", "aaa", "aab");
        assertEquals(reference(input), msd(input));

        assertEquals(List.of(), msd(List.of()));
        assertEquals(List.of("solo"), msd(List.of("solo")));
        assertEquals(List.of("a", "b"), msd(List.of("b", "a")));
        assertEquals(reference(List.of("same", "same", "same")), msd(List.of("same", "same", "same")));
    }

    // ---- stability ---------------------------------------------------------

    private record Item(String key, int seq) {
    }

    @Test
    void stableForEqualKeys() {
        Random r = new Random(7);
        List<Item> input = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            input.add(new Item(randString(r, 4, 3), i)); // small key space -> lots of ties to order stably
        }
        Comparator<Item> byKey = Comparator.comparing(Item::key);
        ByteSequenceEncoder<Item> enc = new ByteSequenceEncoder<>() {
            @Override
            public int length(Item k) {
                return k.key().length() << 1;
            }

            @Override
            public int byteAt(Item k, int index) {
                char c = k.key().charAt(index >> 1);
                return ((index & 1) == 0) ? (c >>> 8) & 0xFF : c & 0xFF;
            }
        };
        SortBuffer<Item> buffer = SortBuffer.of(input, byKey);
        new MsdRadixSortStrategy<>(enc, 2).sort(buffer, SortContext.noop()); // small cutoff -> exercise the radix path
        List<Item> out = buffer.toList();

        List<Item> expected = new ArrayList<>(input);
        expected.sort(byKey); // List.sort is stable -> the reference stable order
        assertEquals(expected, out, "MSD must match a stable reference sort exactly");

        for (int i = 1; i < out.size(); i++) {
            if (out.get(i - 1).key().equals(out.get(i).key())) {
                assertTrue(out.get(i - 1).seq() < out.get(i).seq(), "equal keys keep their input order");
            }
        }
    }

    // ---- byte[] keys -------------------------------------------------------

    private static String hex(byte[] a) {
        StringBuilder sb = new StringBuilder(a.length * 2);
        for (byte x : a) {
            sb.append(String.format("%02x", x & 0xFF));
        }
        return sb.toString();
    }

    @Test
    void byteArrayKeysMatchUnsignedLexOrder() {
        Random r = new Random(123);
        Comparator<byte[]> cmp = ByteSequenceEncoder.byteArrayComparator();
        List<byte[]> input = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            byte[] a = new byte[r.nextInt(6)];
            r.nextBytes(a); // includes negative bytes -> exercises the unsigned view
            input.add(a);
        }
        SortBuffer<byte[]> buffer = SortBuffer.of(input, cmp);
        new MsdRadixSortStrategy<>(ByteSequenceEncoder.forByteArrays()).sort(buffer, SortContext.noop());

        List<String> expected = input.stream().sorted(cmp).map(MsdRadixSortTest::hex).collect(Collectors.toList());
        List<String> actual = buffer.toList().stream().map(MsdRadixSortTest::hex).collect(Collectors.toList());
        assertEquals(expected, actual);
    }

    // ---- facade ------------------------------------------------------------

    @Test
    void beefSortFacadeSortsByteKeys() {
        Random r = new Random(5);
        List<String> input = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            input.add(randString(r, 8, 5));
        }
        List<String> out = BeefSort.with(Comparator.<String>naturalOrder())
                .source(input)
                .sortByteKeys(ByteSequenceEncoder.forStrings());
        assertEquals(reference(input), out);
    }

    // ---- auto-selection ----------------------------------------------------

    @Test
    void engineAutoSelectsMsdRadixForByteSequenceKeys() {
        Random r = new Random(42);
        List<String> input = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            input.add(randString(r, 8, 10));
        }
        BeefSortEngine<String> engine = new BeefSortEngine<>(null, ByteSequenceEncoder.forStrings());
        SortRunResult<String> result = engine.sort(input, Comparator.naturalOrder(), JobSpec.defaults());
        assertEquals(MsdRadixSortStrategy.ID, result.plan().strategy(),
                "engine must auto-select radix.msd when a ByteSequenceEncoder is present");
        assertEquals(reference(input), result.sorted());
    }

    @Test
    void beefSortFacadeAutoSelectsMsdRadix() {
        Random r = new Random(77);
        List<String> input = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            input.add(randString(r, 8, 10));
        }
        SortRunResult<String> result = BeefSort.with(Comparator.<String>naturalOrder())
                .source(input)
                .byteSequenceEncoder(ByteSequenceEncoder.forStrings())
                .run();
        assertEquals(MsdRadixSortStrategy.ID, result.plan().strategy(),
                "facade must auto-select radix.msd when byteSequenceEncoder is set");
        assertEquals(reference(input), result.sorted());
    }
}
