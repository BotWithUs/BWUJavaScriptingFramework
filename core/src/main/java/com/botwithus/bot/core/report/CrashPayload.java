package com.botwithus.bot.core.report;

import com.botwithus.bot.core.runlog.CrashSummary;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * The {@code crash} object of a problem report, cut to the sizes the website
 * accepts: the stack to {@value #MAX_STACK_BYTES} UTF-8 bytes, the newest
 * {@value #MAX_BREADCRUMBS} breadcrumbs, each at most {@value #MAX_BREADCRUMB_CHARS}
 * characters.
 *
 * <p>The strings are already redacted: they come from a run log's
 * {@link CrashSummary}, which redacts before anything is kept. Trimming never
 * splits a character, so a cut can only shorten text, never corrupt it.</p>
 *
 * @param phase       lifecycle phase, spelled as the run log spells it
 * @param exception   {@code class: message}, first line only
 * @param topFrame    first frame inside script code, or {@code unknown}
 * @param stack       the trace, cut from the end when too long
 * @param breadcrumbs the run's newest events, oldest first
 */
public record CrashPayload(String phase, String exception, String topFrame, String stack,
                           List<String> breadcrumbs) {

    /** Largest stack the website keeps, in UTF-8 bytes. */
    public static final int MAX_STACK_BYTES = 16 * 1024;
    /** Most breadcrumbs a report carries; the newest are kept. */
    public static final int MAX_BREADCRUMBS = 50;
    /** Longest breadcrumb, in characters. */
    public static final int MAX_BREADCRUMB_CHARS = 200;

    /** Top two bits of a UTF-8 byte that continues a character. */
    private static final int CONTINUATION_MASK = 0xC0;
    private static final int CONTINUATION_BITS = 0x80;

    public CrashPayload {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(exception, "exception");
        Objects.requireNonNull(topFrame, "topFrame");
        Objects.requireNonNull(stack, "stack");
        breadcrumbs = List.copyOf(breadcrumbs);
    }

    /** The report's view of a run's crash, trimmed to the limits. */
    public static CrashPayload of(CrashSummary crash) {
        List<String> crumbs = crash.breadcrumbs();
        List<String> newest = crumbs.subList(Math.max(0, crumbs.size() - MAX_BREADCRUMBS), crumbs.size());
        return new CrashPayload(crash.phase().wireName(), crash.exception(), crash.topFrame(),
                cutUtf8(crash.stack(), MAX_STACK_BYTES),
                newest.stream().map(c -> cutChars(c, MAX_BREADCRUMB_CHARS)).toList());
    }

    /** {@code text} cut to at most {@code maxBytes} of UTF-8, ending on a whole character. */
    static String cutUtf8(String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        int end = maxBytes;
        // The byte at `end` is the first one dropped. While it continues a
        // character, that character started before the cut: drop all of it.
        while (end > 0 && (bytes[end] & CONTINUATION_MASK) == CONTINUATION_BITS) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    /** {@code text} cut to at most {@code maxChars} characters, never splitting a surrogate pair. */
    static String cutChars(String text, int maxChars) {
        if (text.codePointCount(0, text.length()) <= maxChars) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxChars));
    }
}
