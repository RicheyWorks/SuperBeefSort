package io.github.richeyworks.sbskernels.rust;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * FFM bridge to the native {@code sbsradix} cdylib. Loaded once at class-initialisation; all
 * subsequent sort calls are routed through the single {@link MethodHandle}.
 *
 * <p>Requires JDK 22+ (Panama FFM finalised in JEP 454) and {@code --enable-native-access}
 * on the module / unnamed module. Any failure in the static initialiser sets
 * {@link #AVAILABLE} to {@code false} so the owning {@link RustKernelStrategyProvider} simply
 * registers nothing, leaving the pure-Java {@code radix.lsd} fallback in place.</p>
 */
final class RustRadixBridge {

    private static final boolean AVAILABLE;
    private static final MethodHandle SBS_RADIX_SORT_KEYED;
    private static final boolean LONGS_AVAILABLE;
    private static final MethodHandle SBS_RADIX_SORT_LONGS;

    static {
        boolean ok = false;
        MethodHandle mh = null;
        boolean longsOk = false;
        MethodHandle mhLongs = null;
        try {
            Path libPath = extractNativeLib();
            Linker linker = Linker.nativeLinker();
            // Arena.global() keeps the library mapped for the process lifetime
            SymbolLookup lib = SymbolLookup.libraryLookup(libPath, Arena.global());
            FunctionDescriptor ptrLen = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);
            mh = linker.downcallHandle(
                    lib.find("sbs_radix_sort_keyed").orElseThrow(
                            () -> new UnsatisfiedLinkError("sbs_radix_sort_keyed not found in " + libPath)),
                    ptrLen);
            ok = true;
            // The flat-long entry is newer; tolerate its absence in an older cdylib without disabling
            // the (key, index) path above — only the off-heap long fast path goes unavailable.
            try {
                mhLongs = linker.downcallHandle(
                        lib.find("sbs_radix_sort_longs").orElseThrow(
                                () -> new UnsatisfiedLinkError("sbs_radix_sort_longs not found in " + libPath)),
                        ptrLen);
                longsOk = true;
            } catch (Throwable ignoredLongs) {
                // older kernel without the flat-long entry → off-heap long path unavailable
            }
        } catch (Throwable ignored) {
            // Missing cdylib, unsupported OS, or native-access not granted → AVAILABLE = false
        }
        AVAILABLE = ok;
        SBS_RADIX_SORT_KEYED = mh;
        LONGS_AVAILABLE = longsOk;
        SBS_RADIX_SORT_LONGS = mhLongs;
    }

    static boolean isAvailable() {
        return AVAILABLE;
    }

    /** True when the flat-long entry ({@code sbs_radix_sort_longs}) is present in the loaded cdylib. */
    static boolean isLongsAvailable() {
        return LONGS_AVAILABLE;
    }

    /**
     * Sort {@code count} (key, payload) pairs packed as interleaved u64 values in {@code seg}.
     * {@code seg[2*i]} = u64 key (sign-flipped by caller), {@code seg[2*i+1]} = original index.
     * On return: pairs are in ascending key order; equal-key pairs keep their original order.
     */
    static void sortKeyed(MemorySegment seg, int count) throws Throwable {
        SBS_RADIX_SORT_KEYED.invoke(seg, (long) count);
    }

    /**
     * Sort {@code count} flat u64 keys packed consecutively in {@code seg} (no payload field), ascending,
     * in place. The off-heap long fast path uses this: bulk-copy a {@code long[]} into the segment and
     * sort it in place — no per-element marshaling, no (key, index) pairing.
     */
    static void sortLongs(MemorySegment seg, int count) throws Throwable {
        SBS_RADIX_SORT_LONGS.invoke(seg, (long) count);
    }

    // ── Native-library extraction ────────────────────────────────────────────────────────────

    private static Path extractNativeLib() throws IOException {
        String os   = System.getProperty("os.name", "").toLowerCase();
        String libName;
        String platformDir;
        if (os.contains("windows")) {
            platformDir = "windows-x64";
            libName     = "sbsradix.dll";
        } else if (os.contains("linux")) {
            platformDir = "linux-x64";
            libName     = "libsbsradix.so";
        } else if (os.contains("mac")) {
            platformDir = "macos-x64";
            libName     = "libsbsradix.dylib";
        } else {
            throw new IOException("Unsupported OS: " + os);
        }

        String resource = "/native/" + platformDir + "/" + libName;
        try (InputStream in = RustRadixBridge.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Native library not bundled for this platform: " + resource);
            }
            // Extract to a temp file; System.load / SymbolLookup.libraryLookup need a path
            Path tmp = Files.createTempDirectory("sbsradix").resolve(libName);
            tmp.toFile().deleteOnExit();
            tmp.getParent().toFile().deleteOnExit();
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        }
    }

    private RustRadixBridge() {}
}
