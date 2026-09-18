package com.botwithus.bot.api.draw;

/**
 * The words a captioned command draws, and the style to draw them in.
 *
 * <p>The wire has <b>three spellings of one payload</b> — {@code text} on a text
 * command, {@code label} on a component highlight, and {@code value} plus
 * {@code decimals} for a number the producer formats — and exactly one is legal
 * per command. Sending two is an error rather than a precedence rule, because
 * "which one wins" is not something a caller could guess from a reply that
 * succeeded.</p>
 *
 * <p>This type is that rule made structural: a command holds one caption, so
 * there is no way to express two. {@link None} covers the highlight that carries
 * no caption at all. The producer's errors stay as the contract for other
 * clients; they are simply not reachable from here.</p>
 *
 * <p><b>Why a caption is not part of {@link DrawStyle}.</b> A caption and a font
 * are legal only on {@code text} and {@code component} commands — the producer
 * rejects both on a rect, ellipse, line or poly with
 * {@code only text and component commands take text, label or value} and
 * {@code only text and component commands take a font}. Keeping them here rather
 * than on the shared style means a shape cannot carry one in the first place.
 * The producer's own spec warns callers who build from a shared style object to
 * strip {@code font} for the shapes; this host has nothing to strip.</p>
 */
public sealed interface DrawCaption {

    /** No caption. Valid on a component highlight; not on a text command. */
    DrawCaption NONE = new None();

    /** A caption drawn as-is. */
    static DrawCaption of(String text) {
        return new Literal(text, DrawFont.NORMAL);
    }

    /** A caption drawn as-is, in a named style. */
    static DrawCaption of(String text, DrawFont font) {
        return new Literal(text, font);
    }

    /**
     * A number, scaled. {@code of(1234, 2)} draws {@code "12.34"};
     * {@code of(-5, 2)} draws {@code "-0.05"}; {@code of(-4200, 0)} draws
     * {@code "-4200"}.
     *
     * <p>There is no float anywhere on this wire, deliberately, so a script that
     * wants to caption a distance or a percentage sends a scaled integer and says
     * what it scaled by. The integer part is zero-padded by the producer, so
     * nothing renders as a bare {@code .05}.</p>
     */
    static DrawCaption of(long value, int decimals) {
        return new FixedPoint(value, decimals, DrawFont.NORMAL);
    }

    /** A number, scaled, in a named style. */
    static DrawCaption of(long value, int decimals, DrawFont font) {
        return new FixedPoint(value, decimals, font);
    }

    /** This caption in a different style. */
    DrawCaption withFont(DrawFont font);

    /** True when there is nothing to draw. */
    default boolean isEmpty() {
        return false;
    }

    /** Literal text. */
    record Literal(String text, DrawFont font) implements DrawCaption {

        public Literal {
            if (text == null || text.isEmpty()) {
                throw new IllegalArgumentException("a caption needs non-empty text");
            }
            if (text.length() > DrawLimits.MAX_TEXT_LENGTH) {
                throw new IllegalArgumentException("caption exceeds "
                        + DrawLimits.MAX_TEXT_LENGTH + " UTF-16 units (" + text.length() + ")");
            }
            if (font == null) {
                throw new IllegalArgumentException("font");
            }
        }

        @Override
        public DrawCaption withFont(DrawFont other) {
            return new Literal(text, other);
        }
    }

    /**
     * A fixed-point number the producer formats.
     *
     * @param value    the scaled integer
     * @param decimals how far it was scaled, 0..{@link DrawLimits#MAX_DECIMALS}
     * @param font     the style to draw it in
     */
    record FixedPoint(long value, int decimals, DrawFont font) implements DrawCaption {

        public FixedPoint {
            if (decimals < 0 || decimals > DrawLimits.MAX_DECIMALS) {
                throw new IllegalArgumentException("decimals must be 0.."
                        + DrawLimits.MAX_DECIMALS + ", got " + decimals);
            }
            if (font == null) {
                throw new IllegalArgumentException("font");
            }
        }

        @Override
        public DrawCaption withFont(DrawFont other) {
            return new FixedPoint(value, decimals, other);
        }
    }

    /**
     * The absence of a caption.
     *
     * <p>A variant rather than a {@code null} so the encoder's switch stays
     * exhaustive and "no label" is a case a reader can see handled.</p>
     */
    record None() implements DrawCaption {

        @Override
        public DrawCaption withFont(DrawFont other) {
            return this;
        }

        @Override
        public boolean isEmpty() {
            return true;
        }
    }
}
