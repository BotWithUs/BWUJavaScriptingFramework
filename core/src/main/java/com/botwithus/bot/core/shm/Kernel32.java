package com.botwithus.bot.core.shm;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Panama FFM downcall handles for the four kernel32 entrypoints we need to
 * bind a cross-process file mapping by name.
 *
 * <p>The standard {@code FileChannel.map()} only works on file-backed
 * mappings; a kernel-named section like {@code Local\nxt_snapshot_<pid>}
 * has no file behind it, so we have to call {@code OpenFileMappingW} and
 * {@code MapViewOfFile} directly.</p>
 *
 * <p>Errors returned by these calls are surfaced as the raw NULL/0 sentinel
 * the Win32 API uses; the higher-level {@link SharedRegion} wraps them with
 * {@code GetLastError} context where it matters.</p>
 */
final class Kernel32 {

    // FILE_MAP_READ from MemoryAPI.h. We never write to the mapping from the
    // consumer side — the producer (game-process DLL) is the sole writer.
    static final int FILE_MAP_READ = 0x0004;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32", Arena.global());

    /** {@code HANDLE OpenFileMappingW(DWORD desiredAccess, BOOL inherit, LPCWSTR name);} */
    private static final MethodHandle OPEN_FILE_MAPPING = LINKER.downcallHandle(
            KERNEL32.find("OpenFileMappingW").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,    // desiredAccess
                    ValueLayout.JAVA_INT,    // inherit (BOOL is 4 bytes)
                    ValueLayout.ADDRESS));   // wide-string name

    /** {@code LPVOID MapViewOfFile(HANDLE, DWORD, DWORD, DWORD, SIZE_T);} */
    private static final MethodHandle MAP_VIEW_OF_FILE = LINKER.downcallHandle(
            KERNEL32.find("MapViewOfFile").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,    // mapping handle
                    ValueLayout.JAVA_INT,   // desiredAccess
                    ValueLayout.JAVA_INT,   // fileOffsetHigh
                    ValueLayout.JAVA_INT,   // fileOffsetLow
                    ValueLayout.JAVA_LONG)); // numberOfBytesToMap (SIZE_T = 8 on x64)

    /** {@code BOOL UnmapViewOfFile(LPCVOID baseAddress);} */
    private static final MethodHandle UNMAP_VIEW_OF_FILE = LINKER.downcallHandle(
            KERNEL32.find("UnmapViewOfFile").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS));

    /** {@code BOOL CloseHandle(HANDLE);} */
    private static final MethodHandle CLOSE_HANDLE = LINKER.downcallHandle(
            KERNEL32.find("CloseHandle").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS));

    /** {@code DWORD GetLastError(void);} */
    private static final MethodHandle GET_LAST_ERROR = LINKER.downcallHandle(
            KERNEL32.find("GetLastError").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT));

    private Kernel32() {}

    /**
     * Returns a {@link MemorySegment} pointing at a UTF-16-LE NUL-terminated
     * copy of {@code name}. The caller is responsible for the arena's
     * lifetime — typically a {@link Arena#ofConfined()} dropped after the
     * Win32 call returns. Java's {@code String.getBytes(StandardCharsets.UTF_16LE)}
     * doesn't include a NUL, so we add it explicitly.
     */
    static MemorySegment toWideString(Arena arena, String name) {
        byte[] utf16 = name.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        MemorySegment seg = arena.allocate(utf16.length + 2L);
        MemorySegment.copy(utf16, 0, seg, ValueLayout.JAVA_BYTE, 0, utf16.length);
        // Append null wchar — allocate() zero-fills, but we set explicitly
        // so this remains correct even if the allocator changes behavior.
        seg.set(ValueLayout.JAVA_BYTE, utf16.length,     (byte) 0);
        seg.set(ValueLayout.JAVA_BYTE, utf16.length + 1, (byte) 0);
        return seg;
    }

    /** Returns NULL ({@code MemorySegment.NULL}) on failure; check with {@link #getLastError}. */
    static MemorySegment openFileMapping(int desiredAccess, boolean inherit, String name) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment wname = toWideString(scratch, name);
            return (MemorySegment) OPEN_FILE_MAPPING.invokeExact(
                    desiredAccess, inherit ? 1 : 0, wname);
        } catch (Throwable t) {
            throw new RuntimeException("OpenFileMappingW invocation failed", t);
        }
    }

    /** Returns NULL on failure. */
    static MemorySegment mapViewOfFile(MemorySegment mappingHandle,
                                       int desiredAccess,
                                       long offset,
                                       long bytesToMap) {
        int offHi = (int) (offset >>> 32);
        int offLo = (int) (offset & 0xFFFF_FFFFL);
        try {
            return (MemorySegment) MAP_VIEW_OF_FILE.invokeExact(
                    mappingHandle, desiredAccess, offHi, offLo, bytesToMap);
        } catch (Throwable t) {
            throw new RuntimeException("MapViewOfFile invocation failed", t);
        }
    }

    static boolean unmapViewOfFile(MemorySegment view) {
        try {
            int rv = (int) UNMAP_VIEW_OF_FILE.invokeExact(view);
            return rv != 0;
        } catch (Throwable t) {
            throw new RuntimeException("UnmapViewOfFile invocation failed", t);
        }
    }

    static boolean closeHandle(MemorySegment handle) {
        try {
            int rv = (int) CLOSE_HANDLE.invokeExact(handle);
            return rv != 0;
        } catch (Throwable t) {
            throw new RuntimeException("CloseHandle invocation failed", t);
        }
    }

    static int getLastError() {
        try {
            return (int) GET_LAST_ERROR.invokeExact();
        } catch (Throwable t) {
            throw new RuntimeException("GetLastError invocation failed", t);
        }
    }
}
