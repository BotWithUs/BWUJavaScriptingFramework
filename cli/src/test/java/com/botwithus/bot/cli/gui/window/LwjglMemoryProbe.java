package com.botwithus.bot.cli.gui.window;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * Child-process half of {@link LwjglMemoryBackendTest}: runs a spread of LWJGL
 * memory operations in a fresh JVM, with or without
 * {@link LwjglMemoryBackend#selectFfm()} first, and prints one line per check.
 *
 * <p>It has to be a process of its own for two reasons. LWJGL picks its memory
 * backend once per JVM, and the JDK writes its {@code sun.misc.Unsafe} warnings
 * to the process's original standard error, which no in-process test can
 * capture.</p>
 */
final class LwjglMemoryProbe {

    /** Argument: call {@link LwjglMemoryBackend#selectFfm()} first, as the GUI does. */
    static final String SELECTED = "selected";
    /** Argument: leave LWJGL on its own default backend. */
    static final String UNSELECTED = "unselected";
    /** Printed last, only when every check matched. */
    static final String RESULT_OK = "RESULT ok";

    private static final int BLOCK_BYTES = 64;
    private static final int INT_VALUE = 0x7EADBEEF;
    private static final long LONG_VALUE = 0x0123_4567_89AB_CDEFL;
    private static final byte BYTE_VALUE = 0x5A;
    private static final double DOUBLE_VALUE = 1234.5;
    private static final int LONG_OFFSET = 8;
    private static final int BYTE_OFFSET = 16;
    private static final int DOUBLE_OFFSET = 24;
    private static final int STACK_INTS = 4;
    private static final String TEXT = "BotWithUs ✓ é";

    private LwjglMemoryProbe() {
    }

    public static void main(String[] args) {
        if (SELECTED.equals(args[0])) {
            LwjglMemoryBackend.selectFfm();
        }
        PrintStream out = System.out;
        boolean isOk = checkRawMemory(out) & checkText(out) & checkStack(out);
        if (isOk) {
            out.println(RESULT_OK);
        }
    }

    private static boolean checkRawMemory(PrintStream out) {
        long address = MemoryUtil.nmemAllocChecked(BLOCK_BYTES);
        try {
            MemoryUtil.memSet(address, 0, BLOCK_BYTES);
            MemoryUtil.memPutInt(address, INT_VALUE);
            MemoryUtil.memPutLong(address + LONG_OFFSET, LONG_VALUE);
            MemoryUtil.memPutByte(address + BYTE_OFFSET, BYTE_VALUE);
            MemoryUtil.memPutDouble(address + DOUBLE_OFFSET, DOUBLE_VALUE);
            return report(out, "int", MemoryUtil.memGetInt(address) == INT_VALUE)
                    & report(out, "long", MemoryUtil.memGetLong(address + LONG_OFFSET) == LONG_VALUE)
                    & report(out, "byte", MemoryUtil.memGetByte(address + BYTE_OFFSET) == BYTE_VALUE)
                    & report(out, "double",
                            MemoryUtil.memGetDouble(address + DOUBLE_OFFSET) == DOUBLE_VALUE);
        } finally {
            MemoryUtil.nmemFree(address);
        }
    }

    private static boolean checkText(PrintStream out) {
        ByteBuffer utf8 = MemoryUtil.memUTF8(TEXT);
        try {
            return report(out, "utf8", TEXT.equals(MemoryUtil.memUTF8(MemoryUtil.memAddress(utf8))));
        } finally {
            MemoryUtil.memFree(utf8);
        }
    }

    private static boolean checkStack(PrintStream out) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer ints = stack.mallocInt(STACK_INTS);
            MemoryUtil.memPutInt(MemoryUtil.memAddress(ints), INT_VALUE);
            return report(out, "stack", ints.get(0) == INT_VALUE);
        }
    }

    private static boolean report(PrintStream out, String check, boolean isMatch) {
        out.println("CHECK " + check + "=" + (isMatch ? "ok" : "MISMATCH"));
        return isMatch;
    }
}
