package com.botwithus.bot.api.draw;

import java.time.Duration;

/**
 * Fluent chain for a highlight — the four calls that mark a <i>thing</i> rather
 * than a place.
 *
 * <pre>{@code
 * draw.npc(npc).color(Colors.RED).label(name).submit();        // tracks the NPC
 * draw.self().color(Colors.CYAN).untilCleared().submit();
 * draw.tile("flag", x, y, plane).filled().alpha(80).submit();
 * draw.area("zone", x, y, 5, 5, plane).label("safe").submit();
 * }</pre>
 *
 * <p><b>Why this is a separate type from {@link DrawBuilder} and
 * {@link DrawCaptionBuilder}.</b> Two of the knobs those offer are meaningless
 * here, and this codebase's standing answer to a meaningless knob is to make it
 * unspellable rather than ignored — the same reason {@code draw.rect(...).font(...)}
 * does not compile:</p>
 *
 * <ul>
 *   <li><b>No {@code screen()} / {@code world()}.</b> There is no screen-space
 *       meaning for "the npc with index 42". The producer forces {@code world} on
 *       every highlight before it validates anything, so offering the choice would
 *       be offering one the caller does not have.</li>
 *   <li><b>No {@code closed()}.</b> That is a polyline's flag and a highlight is
 *       never a polyline.</li>
 * </ul>
 *
 * <p>The geometry is not here either — which entity, which tile, how many tiles of
 * footprint, which plane — because each of those is specific to one of the four
 * calls and a wrong one has to be reportable by name. They are named parameters of
 * the {@link DrawTarget} method that opens the chain, so {@code highlight_tile}'s
 * refusal of a {@code w} is a thing this API cannot express rather than a thing it
 * has to defend against.</p>
 *
 * <p>The styling half is delegated to a {@link DrawBuilder} rather than copied, so
 * there is one implementation of what {@code color} or {@code ttl} mean across all
 * three chains. Only the return types differ, which is what keeps them typed.</p>
 */
public final class HighlightBuilder {

    private final DrawTarget target;
    private final DrawBuilder styling;
    private final HighlightFactory factory;
    private DrawCaption caption = DrawCaption.NONE;
    private DrawFont font = DrawFont.NORMAL;

    HighlightBuilder(DrawTarget target, HighlightFactory factory) {
        this.target = target;
        this.styling = DrawBuilder.styleOnly(target);
        this.factory = factory;
    }

    // ------------------------------------------------------------- the caption

    /**
     * Draw this text against the marker the producer resolved.
     *
     * <p>The only way to tell two highlights apart, and it has to travel with the
     * command precisely <i>because</i> the caller never learns where the marker
     * landed. Replaces any caption already set — the wire allows exactly one per
     * command, so these are alternatives rather than additions.</p>
     *
     * <p>The producer spells it {@code label}; sending {@code text} to a highlight is
     * refused with {@code entity names its caption "label", not "text"}. That
     * spelling is chosen by the encoder from the command's own kind, so it is not a
     * mistake reachable from here.</p>
     */
    public HighlightBuilder label(String text) {
        this.caption = DrawCaption.of(text, font);
        return this;
    }

    /**
     * Caption with a scaled number: {@code value(1234, 2)} renders {@code "12.34"}.
     * Replaces any caption already set.
     *
     * <p>There is no float on this wire, deliberately. Send a scaled integer and say
     * what you scaled it by; the producer does the formatting.</p>
     */
    public HighlightBuilder value(long value, int decimals) {
        this.caption = DrawCaption.of(value, decimals, font);
        return this;
    }

    /** Caption with a whole number. */
    public HighlightBuilder value(long value) {
        return value(value, 0);
    }

    /**
     * Draw the caption in a named style. A style name, not a size — the producer
     * owns the size table.
     *
     * <p>Order-independent: the style is remembered and applied to whatever caption
     * the chain ends up with, as well as to one already set. A font on a highlight
     * that never gains a caption draws nothing and sends nothing, which is
     * deliberately not an error — setting the style up front and the caption
     * conditionally is a reasonable thing to write.</p>
     *
     * @throws IllegalArgumentException if {@code font} is null
     */
    public HighlightBuilder font(DrawFont font) {
        if (font == null) {
            throw new IllegalArgumentException("font — use a DrawFont constant, not null");
        }
        this.font = font;
        this.caption = caption.withFont(font);
        return this;
    }

    /** The caption as configured. */
    public DrawCaption caption() {
        return caption;
    }

    // ------------------------------------------------------------- the styling

    /** Packed {@code 0xAARRGGBB}. See {@link Colors}. */
    public HighlightBuilder color(int argb) {
        styling.color(argb);
        return this;
    }

    /** The current colour at a different 0..255 alpha. */
    public HighlightBuilder alpha(int alpha) {
        styling.alpha(alpha);
        return this;
    }

    /** Stroke width for the box drawn around the resolved marker. */
    public HighlightBuilder thickness(int pixels) {
        styling.thickness(pixels);
        return this;
    }

    /** Fill the marker rather than stroking it. */
    public HighlightBuilder filled() {
        styling.filled();
        return this;
    }

    /** Fill or stroke explicitly. */
    public HighlightBuilder filled(boolean fill) {
        styling.filled(fill);
        return this;
    }

    /**
     * Draw order; higher is drawn in front. The only way to control which of several
     * overlapping highlights is visible where they cross, which is exactly the case
     * labels exist to serve.
     */
    public HighlightBuilder z(int order) {
        styling.z(order);
        return this;
    }

    /** Lifetime in milliseconds. Defaults to {@link DrawLimits#DEFAULT_TTL_MS}. */
    public HighlightBuilder ttl(long milliseconds) {
        styling.ttl(milliseconds);
        return this;
    }

    /** Lifetime as a {@link Duration}, truncated to whole milliseconds. */
    public HighlightBuilder ttl(Duration duration) {
        styling.ttl(duration);
        return this;
    }

    /**
     * Keep this highlight until it is replaced, cleared, or the script's connection
     * closes. Not a way to leave something on screen after the script stops:
     * ownership is connection-scoped and the producer drops everything a connection
     * drew when its pipe closes.
     */
    public HighlightBuilder untilCleared() {
        styling.untilCleared();
        return this;
    }

    // --------------------------------------------------------------- terminals

    /** The highlight as configured, without sending it. */
    public DrawCommand.Highlight build() {
        return factory.create(styling.currentStyle(), caption);
    }

    /**
     * Send the highlight, and answer the key it is stored under — which is also how
     * you clear it later.
     *
     * <p>Off {@link Draw} this is one {@code highlight_*} round-trip. Inside a
     * {@link DrawFrame} it only queues; a highlight cannot ride
     * {@code debug_draw_set_batch}, so the frame sends it as its own call when it
     * flushes. See {@link DrawFrame#flush()} for what that means for a frame that
     * fails part-way.</p>
     */
    public String submit() {
        return target.submit(build());
    }
}
