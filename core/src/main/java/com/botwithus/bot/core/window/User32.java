package com.botwithus.bot.core.window;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Optional;

/**
 * Panama FFM downcall handles for the user32 entrypoints {@link GameWindows}
 * needs. LWJGL's {@code User32} binds none of the ones that find a window by
 * its process or read the foreground window, and the cli module may not make
 * restricted calls itself, so they are bound here, in core, which may.
 *
 * <p>Window handles cross as {@code long}: an {@code HWND} is a pointer-sized
 * value that Windows x64 passes in the same integer registers either way, and
 * a {@code long} keeps the callers free of {@code MemorySegment} casts.</p>
 *
 * <p>Initialised on first use only, which {@link GameWindows} guards to
 * Windows.</p>
 */
final class User32 {

    /** {@code GW_OWNER} for {@code GetWindow}: the window's owner. */
    static final int GW_OWNER = 4;
    /** {@code GWLP_HWNDPARENT}: for a top-level window, its owner. */
    static final int GWLP_HWNDPARENT = -8;
    /** A {@code RECT} is four ints: left, top, right, bottom. */
    private static final int RECT_FIELDS = 4;
    private static final int RECT_RIGHT = 2;
    private static final int RECT_BOTTOM = 3;
    /** A {@code POINT} is two ints: x, y. */
    private static final int POINT_FIELDS = 2;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32", Arena.global());

    /** {@code HWND FindWindowExW(HWND parent, HWND childAfter, LPCWSTR cls, LPCWSTR title);} */
    private static final MethodHandle FIND_WINDOW_EX = handle("FindWindowExW", FunctionDescriptor.of(
            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_LONG));

    /** {@code DWORD GetWindowThreadProcessId(HWND, LPDWORD processId);} */
    private static final MethodHandle GET_WINDOW_THREAD_PROCESS_ID = handle("GetWindowThreadProcessId",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

    /** {@code BOOL IsWindow(HWND);} */
    private static final MethodHandle IS_WINDOW = handle("IsWindow",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));

    /** {@code BOOL IsWindowVisible(HWND);} */
    private static final MethodHandle IS_WINDOW_VISIBLE = handle("IsWindowVisible",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));

    /** {@code BOOL IsIconic(HWND);} */
    private static final MethodHandle IS_ICONIC = handle("IsIconic",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));

    /** {@code HWND GetWindow(HWND, UINT command);} */
    private static final MethodHandle GET_WINDOW = handle("GetWindow",
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));

    /** {@code BOOL GetClientRect(HWND, LPRECT);} */
    private static final MethodHandle GET_CLIENT_RECT = handle("GetClientRect",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

    /** {@code BOOL ClientToScreen(HWND, LPPOINT);} */
    private static final MethodHandle CLIENT_TO_SCREEN = handle("ClientToScreen",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

    /** {@code HWND GetForegroundWindow(void);} */
    private static final MethodHandle GET_FOREGROUND_WINDOW = handle("GetForegroundWindow",
            FunctionDescriptor.of(ValueLayout.JAVA_LONG));

    /** {@code LONG_PTR SetWindowLongPtrW(HWND, int index, LONG_PTR value);} */
    private static final MethodHandle SET_WINDOW_LONG_PTR = handle("SetWindowLongPtrW",
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG));

    /** {@code LONG_PTR GetWindowLongPtrW(HWND, int index);} */
    private static final MethodHandle GET_WINDOW_LONG_PTR = handle("GetWindowLongPtrW",
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));

    private User32() {
    }

    private static MethodHandle handle(String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(USER32.find(name).orElseThrow(), descriptor);
    }

    /** The next top-level window after {@code after} (0 for the first), or 0 past the last. */
    static long nextTopLevelWindow(long after) {
        try {
            return (long) FIND_WINDOW_EX.invokeExact(0L, after, 0L, 0L);
        } catch (Throwable t) {
            throw new IllegalStateException("FindWindowExW invocation failed", t);
        }
    }

    /** The id of the process that created {@code window}, or 0 for no such window. */
    static long processIdOf(long window) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment pid = scratch.allocate(ValueLayout.JAVA_INT);
            int thread = (int) GET_WINDOW_THREAD_PROCESS_ID.invokeExact(window, pid);
            return thread == 0 ? 0L : Integer.toUnsignedLong(pid.get(ValueLayout.JAVA_INT, 0));
        } catch (Throwable t) {
            throw new IllegalStateException("GetWindowThreadProcessId invocation failed", t);
        }
    }

    static boolean isWindow(long window) {
        try {
            return (int) IS_WINDOW.invokeExact(window) != 0;
        } catch (Throwable t) {
            throw new IllegalStateException("IsWindow invocation failed", t);
        }
    }

    static boolean isWindowVisible(long window) {
        try {
            return (int) IS_WINDOW_VISIBLE.invokeExact(window) != 0;
        } catch (Throwable t) {
            throw new IllegalStateException("IsWindowVisible invocation failed", t);
        }
    }

    static boolean isIconic(long window) {
        try {
            return (int) IS_ICONIC.invokeExact(window) != 0;
        } catch (Throwable t) {
            throw new IllegalStateException("IsIconic invocation failed", t);
        }
    }

    /** {@code window}'s owner, or 0 when it has none. */
    static long ownerOf(long window) {
        try {
            return (long) GET_WINDOW.invokeExact(window, GW_OWNER);
        } catch (Throwable t) {
            throw new IllegalStateException("GetWindow invocation failed", t);
        }
    }

    /** {@code window}'s client area in screen pixels, or empty when Windows cannot say. */
    static Optional<ClientArea> clientAreaOnScreen(long window) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment rect = scratch.allocate(ValueLayout.JAVA_INT, RECT_FIELDS);
            if ((int) GET_CLIENT_RECT.invokeExact(window, rect) == 0) {
                return Optional.empty();
            }
            MemorySegment origin = scratch.allocate(ValueLayout.JAVA_INT, POINT_FIELDS);
            if ((int) CLIENT_TO_SCREEN.invokeExact(window, origin) == 0) {
                return Optional.empty();
            }
            int width = rect.getAtIndex(ValueLayout.JAVA_INT, RECT_RIGHT) - rect.getAtIndex(ValueLayout.JAVA_INT, 0);
            int height = rect.getAtIndex(ValueLayout.JAVA_INT, RECT_BOTTOM) - rect.getAtIndex(ValueLayout.JAVA_INT, 1);
            return Optional.of(new ClientArea(origin.getAtIndex(ValueLayout.JAVA_INT, 0),
                    origin.getAtIndex(ValueLayout.JAVA_INT, 1), width, height));
        } catch (Throwable t) {
            throw new IllegalStateException("GetClientRect/ClientToScreen invocation failed", t);
        }
    }

    static long foregroundWindow() {
        try {
            return (long) GET_FOREGROUND_WINDOW.invokeExact();
        } catch (Throwable t) {
            throw new IllegalStateException("GetForegroundWindow invocation failed", t);
        }
    }

    /** Sets one of {@code window}'s values; returns the one it replaces. */
    static long setWindowLongPtr(long window, int index, long value) {
        try {
            return (long) SET_WINDOW_LONG_PTR.invokeExact(window, index, value);
        } catch (Throwable t) {
            throw new IllegalStateException("SetWindowLongPtrW invocation failed", t);
        }
    }

    static long windowLongPtr(long window, int index) {
        try {
            return (long) GET_WINDOW_LONG_PTR.invokeExact(window, index);
        } catch (Throwable t) {
            throw new IllegalStateException("GetWindowLongPtrW invocation failed", t);
        }
    }
}
