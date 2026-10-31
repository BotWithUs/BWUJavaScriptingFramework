package com.botwithus.bot.cli.gui;

import java.util.function.ToDoubleFunction;

/**
 * Makes text a script supplied safe to show in a single line of the GUI.
 *
 * <p>A script's name and phrases come from the script itself, so they may hold line breaks,
 * control characters or invisible formatting characters (such as the ones that reverse the
 * direction of the text after them). Any of those could make one script's prompt read like
 * another's. {@link #line} removes them and caps the length. {@link #fitKey} shortens a script
 * key while keeping both of the parts that tell two keys apart.</p>
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
     * {@code raw} cleaned as {@link #line} does, then fitted to {@code maxChars} by
     * {@link #fitKey}: for a script key, where both ends tell two keys apart.
     */
    public static String key(String raw, int maxChars) {
        return fitKey(line(raw, Integer.MAX_VALUE), maxChars, SafeText::codePoints);
    }

    /**
     * A script key, {@code <module>/<class>}, shortened to fit {@code maxWidth} as measured by
     * {@code width}, keeping what tells two keys apart: the whole module and the end of the
     * class. The class's package gives way first. Only when the module and the class's simple
     * name do not fit together is the middle of the module cut. A key with no {@code /} has its
     * middle cut.
     */
    public static String fitKey(String key, double maxWidth, ToDoubleFunction<String> width) {
        if (width.applyAsDouble(key) <= maxWidth) {
            return key;
        }
        int slash = key.indexOf('/');
        if (slash < 0) {
            return keepEnds(key, maxWidth, width);
        }
        String module = key.substring(0, slash + 1);
        String type = key.substring(slash + 1);
        int simple = simpleNameStart(type);
        for (int start = nextCodePoint(type, 0); start <= simple; start = nextCodePoint(type, start)) {
            String shown = module + ELLIPSIS + type.substring(start);
            if (width.applyAsDouble(shown) <= maxWidth) {
                return shown;
            }
        }
        String tail = simple == 0 ? type : ELLIPSIS + type.substring(simple);
        return keepEnds(module, maxWidth - width.applyAsDouble(tail), width) + tail;
    }

    /** {@code text} with its middle replaced by an ellipsis, keeping as much of both ends as fits. */
    static String keepEnds(String text, double maxWidth, ToDoubleFunction<String> width) {
        if (width.applyAsDouble(text) <= maxWidth) {
            return text;
        }
        int[] cps = text.codePoints().toArray();
        for (int kept = cps.length - 1; kept > 0; kept--) {
            int head = (kept + 1) / 2;
            int tail = kept - head;
            String shown = new String(cps, 0, head) + ELLIPSIS + new String(cps, cps.length - tail, tail);
            if (width.applyAsDouble(shown) <= maxWidth) {
                return shown;
            }
        }
        return ELLIPSIS;
    }

    /** Where the class's simple name starts: after its last {@code .} or {@code $}. */
    private static int simpleNameStart(String type) {
        return Math.max(type.lastIndexOf('.'), type.lastIndexOf('$')) + 1;
    }

    private static int nextCodePoint(String text, int index) {
        return index >= text.length() ? text.length() + 1 : index + Character.charCount(text.codePointAt(index));
    }

    private static double codePoints(String text) {
        return text.codePointCount(0, text.length());
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
