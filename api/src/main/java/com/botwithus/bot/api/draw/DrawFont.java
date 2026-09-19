package com.botwithus.bot.api.draw;

/**
 * The text styles the producer can draw a caption in.
 *
 * <p><b>A style name, not a size.</b> The producer owns the size table
 * deliberately, so the set of font objects it holds is a compile-time constant
 * rather than something a script can grow. There is no way to ask for "14pt".</p>
 *
 * <p>Mirrors {@code overlay::FontStyle} in the producer. An unknown name is
 * rejected on the wire with {@code unknown font (want normal, small, large or
 * heading)} — which this host cannot provoke, because the name is only ever
 * produced from this enum.</p>
 */
public enum DrawFont {

    /** The default. */
    NORMAL("normal"),
    SMALL("small"),
    LARGE("large"),
    HEADING("heading");

    private final String wireName;

    DrawFont(String wireName) {
        this.wireName = wireName;
    }

    /** The lower-case token this style is spelled as in the {@code font} field. */
    public String wireName() {
        return wireName;
    }

    /**
     * The style a producer reply spelled, defaulting to {@link #NORMAL} for a
     * name this build does not know — the producer is versioned separately and
     * may grow a style, and a decoder must not throw when it does.
     */
    public static DrawFont fromWireName(String name) {
        for (DrawFont font : values()) {
            if (font.wireName.equals(name)) {
                return font;
            }
        }
        return NORMAL;
    }
}
