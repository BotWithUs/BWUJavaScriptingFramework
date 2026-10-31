package com.botwithus.bot.cli.gui;

import java.util.function.ToDoubleFunction;

/**
 * Makes text a script supplied safe to show in a single line of the GUI.
 *
 * <p>A script's name and phrases come from the script itself, so they may hold line breaks,
 * control characters or invisible formatting characters (such as the ones that reverse the
 * direction of the text after them). Any of those could make one script's prompt read like
 * another's. {@link #line} removes them and caps the length; {@link #keepTail} shortens from
 * the front, for text whose end is what tells two values apart.</p>
 */
public final class SafeText {

    /** The marker that stands in for removed text. */
    public static final String ELLIPSIS = "…";

    private SafeText() {
    }

    /**
     * {@code raw} as one line: every control or formatting character replaced by a space, runs
     * of whitespace collapsed, trimmed, and cut to {@code maxChars} (ending in an ellipsis).
     */
    public static String line(String raw, int maxChars) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        boolean isSpace = true;
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (isHidden(cp) || Character.isWhitespace(cp)) {
                if (!isSpace) {
                    out.append(' ');
                    isSpace = true;
                }
                continue;
            }
            out.appendCodePoint(cp);
            isSpace = false;
        }
        String clean = out.toString().strip();
        return clean.codePointCount(0, clean.length()) <= maxChars ? clean : cut(clean, maxChars);
    }

    /**
     * {@code raw} cleaned as {@link #line} does, but cut from the front to {@code maxChars},
     * so the end survives: for keys, whose last part tells two of them apart.
     */
    public static String lineKeepingTail(String raw, int maxChars) {
        String clean = line(raw, Integer.MAX_VALUE);
        int length = clean.codePointCount(0, clean.length());
        if (length <= maxChars) {
            return clean;
        }
        int start = clean.offsetByCodePoints(0, length - Math.max(0, maxChars - 1));
        return ELLIPSIS + clean.substring(start).stripLeading();
    }

    /**
     * The end of {@code text} that fits in {@code maxWidth} as measured by {@code width},
     * after an ellipsis: the front gives way first, so the distinguishing end stays visible.
     */
    public static String keepTail(String text, double maxWidth, ToDoubleFunction<String> width) {
        if (width.applyAsDouble(text) <= maxWidth) {
            return text;
        }
        for (int start = 1; start < text.length(); start++) {
            if (Character.isLowSurrogate(text.charAt(start))) {
                continue;
            }
            String shown = ELLIPSIS + text.substring(start);
            if (width.applyAsDouble(shown) <= maxWidth) {
                return shown;
            }
        }
        return ELLIPSIS;
    }

    private static boolean isHidden(int cp) {
        int type = Character.getType(cp);
        return Character.isISOControl(cp) || type == Character.FORMAT || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR || type == Character.PRIVATE_USE
                || type == Character.UNASSIGNED || type == Character.SURROGATE;
    }

    private static String cut(String text, int maxChars) {
        int end = text.offsetByCodePoints(0, Math.max(0, maxChars - 1));
        return text.substring(0, end).stripTrailing() + ELLIPSIS;
    }
}
