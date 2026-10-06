package com.botwithus.bot.core.runlog;

import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Renders a throwable the way a run log carries it: every line of the trace,
 * causes and suppressed exceptions included, indented by two spaces (spec §1.2).
 *
 * <p>Works on logback's {@link IThrowableProxy} so a logging event and a crash
 * the runner caught go through one renderer. Iterative rather than recursive,
 * so it is safe to call straight after a {@link StackOverflowError}, and it
 * stops at a cause cycle instead of looping.</p>
 */
public final class ThrowableText {

    static final String INDENT = "  ";
    /** Deepest cause/suppressed nesting rendered; a guard, not an expected shape. */
    static final int MAX_NESTED = 64;

    private ThrowableText() {
    }

    /** The trace of {@code error}, one line per element, each already indented. */
    public static List<String> lines(Throwable error) {
        return error == null ? List.of() : lines(new ThrowableProxy(error));
    }

    /** As {@link #lines(Throwable)}, from a logging event's proxy. */
    static List<String> lines(IThrowableProxy root) {
        List<String> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        Deque<Entry> pending = new ArrayDeque<>();
        pending.push(new Entry("", root));
        int rendered = 0;
        while (!pending.isEmpty() && rendered < MAX_NESTED) {
            Entry next = pending.pop();
            rendered++;
            renderOne(next, out);
            pushChildren(next.proxy(), pending);
        }
        return out;
    }

    /** {@code class: message}, the first line of a trace without its indent. */
    static String headline(IThrowableProxy proxy) {
        String message = proxy.getMessage();
        return message == null ? proxy.getClassName() : proxy.getClassName() + ": " + message;
    }

    private static void renderOne(Entry entry, List<String> out) {
        IThrowableProxy proxy = entry.proxy();
        if (proxy.isCyclic()) {
            out.add(INDENT + entry.prefix() + "[CIRCULAR REFERENCE: " + headline(proxy) + "]");
            return;
        }
        String[] headlineLines = headline(proxy).split("\\R", -1);
        out.add(INDENT + entry.prefix() + headlineLines[0]);
        for (int i = 1; i < headlineLines.length; i++) {
            out.add(INDENT + headlineLines[i]);
        }
        StackTraceElementProxy[] frames = proxy.getStackTraceElementProxyArray();
        int common = proxy.getCommonFrames();
        int shown = frames == null ? 0 : frames.length - common;
        for (int i = 0; i < shown; i++) {
            out.add(INDENT + "at " + frames[i].getStackTraceElement());
        }
        if (common > 0) {
            out.add(INDENT + "... " + common + " more");
        }
    }

    /** Pushed in reverse so suppressed come out before the cause, as the JDK prints them. */
    private static void pushChildren(IThrowableProxy proxy, Deque<Entry> pending) {
        if (proxy.isCyclic()) {
            return;
        }
        if (proxy.getCause() != null) {
            pending.push(new Entry("Caused by: ", proxy.getCause()));
        }
        IThrowableProxy[] suppressed = proxy.getSuppressed();
        if (suppressed != null) {
            for (int i = suppressed.length - 1; i >= 0; i--) {
                pending.push(new Entry("Suppressed: ", suppressed[i]));
            }
        }
    }

    private record Entry(String prefix, IThrowableProxy proxy) {
    }
}
