import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Arrays;
import java.util.Random;

/**
 * Phase 2 vertical slice (proof-of-concept): sort a Java {@code long[]} by handing an off-heap
 * {@link MemorySegment} to the Rust radix kernel ({@code libsbsradix.so}) via Panama FFM (a downcall).
 * Signed longs map to the kernel's unsigned order via {@code XOR Long.MIN_VALUE}, the same trick the
 * pure-Java {@code RadixSortStrategy} uses, so negatives and the extremes sort correctly.
 *
 * <p>Build &amp; run (JDK 21 preview; finalized in JDK 22 — drop {@code --enable-preview} there):</p>
 * <pre>
 *   rustc --crate-type=cdylib -O radix.rs -o libsbsradix.so
 *   javac --release 21 --enable-preview SbsRadixFfm.java
 *   java  --enable-preview --enable-native-access=ALL-UNNAMED SbsRadixFfm
 * </pre>
 * (If the cdylib dynamically links the Rust std, put it on {@code LD_LIBRARY_PATH} when running.)
 */
public class SbsRadixFfm {

    private static final MethodHandle SORT;
    static {
        Linker linker = Linker.nativeLinker();
        SymbolLookup lib = SymbolLookup.libraryLookup("./libsbsradix.so", Arena.global());
        SORT = linker.downcallHandle(
                lib.find("sbs_radix_sort_u64").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
    }

    /** Sort {@code a} ascending (signed) using the off-heap Rust radix kernel. */
    static void sortOffHeap(long[] a) throws Throwable {
        int n = a.length;
        if (n < 2) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate((long) n * Long.BYTES, Long.BYTES);
            for (int i = 0; i < n; i++) {
                seg.setAtIndex(ValueLayout.JAVA_LONG, i, a[i] ^ Long.MIN_VALUE); // signed -> unsigned order
            }
            SORT.invoke(seg, (long) n);
            for (int i = 0; i < n; i++) {
                a[i] = seg.getAtIndex(ValueLayout.JAVA_LONG, i) ^ Long.MIN_VALUE;
            }
        }
    }

    public static void main(String[] args) throws Throwable {
        Random rng = new Random(42);
        int fails = 0, checks = 0;
        for (int t = 0; t < 300; t++) {
            long[] a = new long[rng.nextInt(2000)];
            for (int i = 0; i < a.length; i++) {
                a[i] = rng.nextLong() % 1_000_000L; // includes negatives
            }
            long[] want = a.clone();
            Arrays.sort(want);
            sortOffHeap(a);
            if (!Arrays.equals(a, want)) {
                fails++;
            }
            checks++;
        }
        System.out.println("FFM -> Rust radix: " + (fails == 0 ? "OK" : "FAIL")
                + " (" + checks + " random arrays incl. negatives, " + fails + " mismatches)");

        long[] ex = {5, -3, 0, 9, -3, 2, Long.MIN_VALUE, Long.MAX_VALUE};
        sortOffHeap(ex);
        System.out.println("example sorted: " + Arrays.toString(ex));
        System.exit(fails == 0 ? 0 : 1);
    }
}
