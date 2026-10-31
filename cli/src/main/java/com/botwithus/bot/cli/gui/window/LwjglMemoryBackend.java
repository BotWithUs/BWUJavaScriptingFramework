package com.botwithus.bot.cli.gui.window;

import org.lwjgl.system.Configuration;

/**
 * Selects LWJGL's {@code java.lang.foreign} memory backend, the only one that
 * never calls {@code sun.misc.Unsafe}.
 *
 * <p>Since LWJGL 3.4.2 the default backend on JDK 25 is still Unsafe-based: the
 * {@code sun.misc.Unsafe} one unless {@code jdk.internal.misc} is exported to
 * LWJGL. Those memory-access methods are terminally deprecated (JEP 471/498),
 * so the default prints a startup warning today and breaks when the methods
 * are removed. The FFM backend needs nothing beyond the native access LWJGL
 * already has for its downcalls.</p>
 *
 * <p>LWJGL reads this setting once, in {@code MemoryUtil}'s static initialiser,
 * and ignores any later change. So {@link #selectFfm()} must run before the
 * first LWJGL class that touches memory initialises. Every {@code main} in
 * this module calls it as its first statement. Calling it is safe in a process
 * that never reaches LWJGL: {@code Configuration}'s own initialiser only reads
 * system properties, so selecting the backend initialises nothing else.</p>
 *
 * <p>The choice is made in code rather than as {@code -D} on a command line
 * because the official launcher starts the runtime's {@code java.exe} with
 * its own argument line, so a JVM option baked into {@code bin/jbot.bat} or
 * the jlink launcher would not reach the shipped host.</p>
 *
 * <p>LWJGL silently falls back to its default backend when the requested one
 * fails to load, so nothing at runtime says the selection was lost.
 * {@code LwjglMemoryBackendTest} is the guard: it runs LWJGL memory operations
 * in a child JVM with {@code --sun-misc-unsafe-memory-access=debug} and fails
 * if the JDK reports a single Unsafe call.</p>
 */
public final class LwjglMemoryBackend {

    /** LWJGL's value for its FFM backend ({@code Configuration.MEMORY_BACKEND}). */
    static final String FFM = "ffm";

    private LwjglMemoryBackend() {
    }

    /**
     * Selects the FFM backend. Must run before any LWJGL class that touches
     * memory initialises; see the class documentation.
     */
    public static void selectFfm() {
        Configuration.MEMORY_BACKEND.set(FFM);
    }
}
