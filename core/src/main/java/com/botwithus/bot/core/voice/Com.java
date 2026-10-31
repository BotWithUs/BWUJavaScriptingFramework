package com.botwithus.bot.core.voice;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Just enough COM, through the Foreign Function &amp; Memory API, to drive SAPI: the
 * {@code ole32} entry points, typed calls through an interface's vtable, GUIDs and
 * {@code HRESULT} checks.
 *
 * <p>A COM interface pointer points at a pointer to its vtable, an array of function
 * pointers. Slot {@code n} is called with the interface pointer as its first argument. The
 * {@code call*} methods are named for the argument shape after that pointer
 * ({@code A} address, {@code I} 32-bit int, {@code S} 16-bit, {@code J} 64-bit,
 * {@code F} float), each backed by one downcall handle whose target address is supplied per
 * call. Only the thread that called {@link #initializeThread()} may use an instance.</p>
 */
final class Com {

    /** {@code S_OK}. */
    static final int S_OK = 0;
    /** {@code S_FALSE}: succeeded, with nothing (more) to report. */
    static final int S_FALSE = 1;
    /** Win32 {@code TRUE} as a {@code BOOL} argument. */
    static final int TRUE = 1;
    /** Win32 {@code FALSE} as a {@code BOOL} argument. */
    static final int FALSE = 0;
    /** {@code IUnknown::Release}, slot 2 of every COM vtable. */
    static final int RELEASE = 2;

    /** {@code COINIT_MULTITHREADED} (combaseapi.h {@code COINITBASE_MULTITHREADED = 0x0}). */
    private static final int COINIT_MULTITHREADED = 0x0;
    /** {@code CLSCTX_INPROC_SERVER} (wtypesbase.h). */
    private static final int CLSCTX_INPROC_SERVER = 0x1;
    private static final int GUID_BYTES = 16;

    private final MethodHandle coInitializeEx;
    private final MethodHandle coUninitialize;
    private final MethodHandle coCreateInstance;
    private final MethodHandle coTaskMemFree;
    private final MethodHandle shape;
    private final MethodHandle shapeI;
    private final MethodHandle shapeS;
    private final MethodHandle shapeA;
    private final MethodHandle shapeAI;
    private final MethodHandle shapeJA;
    private final MethodHandle shapeJJ;
    private final MethodHandle shapeIAA;
    private final MethodHandle shapeAAI;
    private final MethodHandle shapeAIIIA;
    private final MethodHandle shapeAAAAIFA;
    private final MethodHandle shapeAIAAJ;

    /**
     * Binds {@code ole32}.
     *
     * @throws IllegalArgumentException if {@code ole32} cannot be loaded, as on any system
     *                                  but Windows
     */
    Com() {
        Linker linker = Linker.nativeLinker();
        SymbolLookup ole32 = SymbolLookup.libraryLookup("ole32", Arena.global());
        coInitializeEx = linker.downcallHandle(ole32.findOrThrow("CoInitializeEx"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        coUninitialize = linker.downcallHandle(ole32.findOrThrow("CoUninitialize"),
                FunctionDescriptor.ofVoid());
        coCreateInstance = linker.downcallHandle(ole32.findOrThrow("CoCreateInstance"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        coTaskMemFree = linker.downcallHandle(ole32.findOrThrow("CoTaskMemFree"),
                FunctionDescriptor.ofVoid(ADDRESS));
        shape = virtual(linker);
        shapeI = virtual(linker, JAVA_INT);
        shapeS = virtual(linker, JAVA_SHORT);
        shapeA = virtual(linker, ADDRESS);
        shapeAI = virtual(linker, ADDRESS, JAVA_INT);
        shapeJA = virtual(linker, JAVA_LONG, ADDRESS);
        shapeJJ = virtual(linker, JAVA_LONG, JAVA_LONG);
        shapeIAA = virtual(linker, JAVA_INT, ADDRESS, ADDRESS);
        shapeAAI = virtual(linker, ADDRESS, ADDRESS, JAVA_INT);
        shapeAIIIA = virtual(linker, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS);
        shapeAAAAIFA = virtual(linker, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_FLOAT, ADDRESS);
        shapeAIAAJ = virtual(linker, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG);
    }

    /** {@code HRESULT (this, args...)}, with the function address as a leading parameter. */
    private static MethodHandle virtual(Linker linker, MemoryLayout... args) {
        MemoryLayout[] all = new MemoryLayout[args.length + 1];
        all[0] = ADDRESS;
        System.arraycopy(args, 0, all, 1, args.length);
        return linker.downcallHandle(FunctionDescriptor.of(JAVA_INT, all));
    }

    /** Joins the calling thread to the multithreaded apartment. */
    void initializeThread() {
        try {
            check((int) coInitializeEx.invokeExact(MemorySegment.NULL, COINIT_MULTITHREADED),
                    "CoInitializeEx");
        } catch (SapiException e) {
            throw e;
        } catch (Throwable t) {
            throw new SapiException("CoInitializeEx", t);
        }
    }

    /** Balances {@link #initializeThread()}. */
    void uninitializeThread() {
        try {
            coUninitialize.invokeExact();
        } catch (Throwable t) {
            throw new SapiException("CoUninitialize", t);
        }
    }

    /** {@code CoCreateInstance} of an in-process server; the caller owns the reference. */
    MemorySegment create(String clsid, String iid) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int hr = (int) coCreateInstance.invokeExact(guid(arena, clsid), MemorySegment.NULL,
                    CLSCTX_INPROC_SERVER, guid(arena, iid), out);
            check(hr, "CoCreateInstance " + clsid);
            return out.get(ADDRESS, 0);
        } catch (SapiException e) {
            throw e;
        } catch (Throwable t) {
            throw new SapiException("CoCreateInstance " + clsid, t);
        }
    }

    /** {@code CoTaskMemFree}; a null pointer is a no-op, as for the native call. */
    void free(MemorySegment pointer) {
        try {
            coTaskMemFree.invokeExact(pointer);
        } catch (Throwable t) {
            throw new SapiException("CoTaskMemFree", t);
        }
    }

    /** {@code IUnknown::Release} on {@code self} unless it is null. Returns null for reassignment. */
    MemorySegment release(MemorySegment self) {
        if (!self.equals(MemorySegment.NULL)) {
            call(self, RELEASE);
        }
        return MemorySegment.NULL;
    }

    /** A NUL-terminated wide string ({@code LPCWSTR}) for {@code text}. */
    static MemorySegment wide(Arena arena, String text) {
        return arena.allocateFrom(text, StandardCharsets.UTF_16LE);
    }

    /** The wide string at {@code pointer}, which must be NUL-terminated. */
    static String readWide(MemorySegment pointer) {
        return pointer.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_16LE);
    }

    /** A {@code GUID} in native memory. */
    static MemorySegment guid(Arena arena, String text) {
        return arena.allocateFrom(JAVA_BYTE, guidBytes(text));
    }

    /**
     * The 16 bytes of a {@code GUID} as Windows lays it out: {@code Data1} (32-bit),
     * {@code Data2} and {@code Data3} (16-bit) little-endian, then {@code Data4} as written.
     */
    static byte[] guidBytes(String text) {
        UUID uuid = UUID.fromString(text);
        long high = uuid.getMostSignificantBits();
        ByteBuffer bytes = ByteBuffer.allocate(GUID_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt((int) (high >>> Integer.SIZE));
        bytes.putShort((short) (high >>> Short.SIZE));
        bytes.putShort((short) high);
        bytes.order(ByteOrder.BIG_ENDIAN).putLong(uuid.getLeastSignificantBits());
        return bytes.array();
    }

    /** Throws if {@code hr} is a failure code; returns it otherwise ({@code S_OK} or {@code S_FALSE}). */
    static int check(int hr, String what) {
        if (hr < 0) {
            throw new SapiException(what, hr);
        }
        return hr;
    }

    int call(MemorySegment self, int slot) {
        try {
            return (int) shape.invokeExact(method(self, slot), self);
        } catch (Throwable t) {
            throw failed(slot, t);
        }
    }

    int callI(MemorySegment self, int slot, int a) {
        try {
            return (int) shapeI.invokeExact(method(self, slot), self, a);
        } catch (Throwable t) {
            throw failed(slot, t);
        }
    }

    int callS(MemorySegment self, int slot, short a) {
        try {
            return (int) shapeS.invokeExact(method(self, slot), self, a);
        } catch (Throwable t) {
            throw failed(slot, t);
        }
    }

    int callA(MemorySegment self, int slot, MemorySegment a) {
        try {
            return (int) shapeA.invokeExact(method(self, slot), self, a);
        } catch (Throwable t) {
            throw failed(slot, t);
        }
    }

    int callAI(MemorySegment self, int slot, MemorySegment a, int b) {
        try {
            return (int) shapeAI.invokeExact(method(self, slot), self, a, b);
        } catch (Throwable t) {
            throw failed(slot, t);
        }
    }

    int callJA(MemorySegment self, int slot, long a, MemorySegment b) {
        try {
            return (int) shapeJA.invokeExact(method(self, slot), self, a, b);
        } catch (Throwable t) {
            throw failed(slot, t);
        }
    }

    int callJJ(MemorySegment self, int slot, long a, long b) {
        try {
            return (int) shapeJJ.invokeExact(method(self, slot), self, a, b);
        } catch (Throwable t) {
            throw failed(slot, t);
        }
    }

    int callIAA(MemorySegment self, int slot, int a, MemorySegment b, MemorySegment c) {
        try {
            return (int) shapeIAA.invokeExact(method(self, slot), self, a, b, c);
        } catch (Throwable t) {
            throw failed(slot, t);
        }
    }

    int callAAI(MemorySegment self, int slot, MemorySegment a, MemorySegment b, int c) {
        try {
            return (int) shapeAAI.invokeExact(method(self, slot), self, a, b, c);
        } catch (Throwable t) {
            throw failed(slot, t);
        }
    }

    int callAIIIA(MemorySegment self, int slot, MemorySegment a, int b, int c, int d, MemorySegment e) {
        try {
            return (int) shapeAIIIA.invokeExact(method(self, slot), self, a, b, c, d, e);
        } catch (Throwable t) {
            throw failed(slot, t);
        }
    }

    int callAAAAIFA(MemorySegment self, int slot, MemorySegment a, MemorySegment b, MemorySegment c,
                    MemorySegment d, int e, float f, MemorySegment g) {
        try {
            return (int) shapeAAAAIFA.invokeExact(method(self, slot), self, a, b, c, d, e, f, g);
        } catch (Throwable t) {
            throw failed(slot, t);
        }
    }

    int callAIAAJ(MemorySegment self, int slot, MemorySegment a, int b, MemorySegment c,
                  MemorySegment d, long e) {
        try {
            return (int) shapeAIAAJ.invokeExact(method(self, slot), self, a, b, c, d, e);
        } catch (Throwable t) {
            throw failed(slot, t);
        }
    }

    /** The function pointer in {@code self}'s vtable at {@code slot}. */
    private static MemorySegment method(MemorySegment self, int slot) {
        MemorySegment vtable = self.reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);
        return vtable.reinterpret(ADDRESS.byteSize() * (slot + 1)).getAtIndex(ADDRESS, slot);
    }

    private static SapiException failed(int slot, Throwable t) {
        return new SapiException("COM call to vtable slot " + slot, t);
    }
}
