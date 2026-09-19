package com.botwithus.bot.api.draw;

import java.time.Duration;

/**
 * Fluent chain for the two commands that draw words — a {@code text} command and
 * a captioned component highlight.
 *
 * <pre>{@code
 * draw.component(1473, 5).color(Colors.CYAN).label("Inventory").submit();
 * draw.text("state", 40, 140, "banking").font(DrawFont.SMALL).submit();
 * draw.value("dps", 40, 160, 1234, 2).font(DrawFont.LARGE).submit();   // "12.34"
 * }</pre>
 *
 * <p><b>Why this is a separate type from {@link DrawBuilder}.</b> A caption and a
 * font are legal only on these two kinds; the producer rejects both on a rect,
 * ellipse, line or poly. Splitting the chain means {@code draw.rect(...).font(...)}
 * does not compile, rather than compiling and being refused at run time on a
 * round-trip the script has already paid for. The producer's spec warns callers
 * who build commands from a shared style object to strip {@code font} for the
 * shapes — there is nothing to strip here, because a shape's builder never had
 * one.</p>
 *
 * <p>The styling half is delegated to a {@link DrawBuilder} rather than copied,
 * so there is one implementation of what {@code color} or {@code ttl} mean. Only
 * the return types differ, which is what keeps the chain typed.</p>
 */
public final class DrawCaptionBuilder {

    private final DrawTarget target;
    private final DrawBuilder styling;
    private final CaptionCommandFactory factory;
    private DrawCaption caption;
    private DrawFont font = DrawFont.NORMAL;

    DrawCaptionBuilder(DrawTarget target, DrawBuilder styling, CaptionCommandFactory factory,
                       DrawCaption caption) {
        this.target = target;
        this.styling = styling;
        this.factory = factory;
        this.caption = caption;
    }

    // ------------------------------------------------------------- the caption

    /**
     * Draw this text. Replaces any caption already set — the wire allows exactly
     * one caption per command, so these are alternatives rather than additions.
     */
    public DrawCaptionBuilder label(String text) {
        this.caption = DrawCaption.of(text, font);
        return this;
    }

    /**
     * Draw this scaled number: {@code value(1234, 2)} renders {@code "12.34"}.
     * Replaces any caption already set.
     *
     * <p>There is no float on this wire, deliberately. Send a scaled integer and
     * say what you scaled it by.</p>
     */
    public DrawCaptionBuilder value(long value, int decimals) {
        this.caption = DrawCaption.of(value, decimals, font);
        return this;
    }

    /** Draw this whole number. */
    public DrawCaptionBuilder value(long value) {
        return value(value, 0);
    }

    /**
     * Draw the caption in a named style. A style name, not a size.
     *
     * <p>Order-independent: the style is remembered and applied to whatever caption
     * the chain ends up with, as well as to one already set. {@code font(...)}
     * before {@code label(...)} and after it mean the same thing — which they would
     * not if the style lived only on the caption value, because
     * {@link DrawCaption#NONE} has nowhere to keep it.</p>
     *
     * <p>A font on a command that never gains a caption draws nothing and sends
     * nothing: a highlight with no label has no words to style. That is deliberately
     * <b>not</b> an error, because setting the style up front and the caption
     * conditionally is a reasonable thing to write:</p>
     *
     * <pre>{@code
     * DrawCaptionBuilder box = draw.component(iface, comp).font(DrawFont.HEADING);
     * if (showName) {
     *     box.label(npcName);
     * }
     * box.submit();
     * }</pre>
     *
     * @throws IllegalArgumentException if {@code font} is null. Rejected here rather
     *         than carried, because a null survives the {@link DrawCaption#NONE}
     *         path — {@code None.withFont} ignores its argument — and would
     *         otherwise surface from inside a later {@code label()} or
     *         {@code value()}, naming the wrong call.
     */
    public DrawCaptionBuilder font(DrawFont font) {
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
    public DrawCaptionBuilder color(int argb) {
        styling.color(argb);
        return this;
    }

    /** The current colour at a different 0..255 alpha. */
    public DrawCaptionBuilder alpha(int alpha) {
        styling.alpha(alpha);
        return this;
    }

    /** Stroke width, for the box a component highlight draws around its rect. */
    public DrawCaptionBuilder thickness(int pixels) {
        styling.thickness(pixels);
        return this;
    }

    /** Fill rather than stroke. Meaningful for a component highlight's box. */
    public DrawCaptionBuilder filled() {
        styling.filled();
        return this;
    }

    /** Fill or stroke explicitly. */
    public DrawCaptionBuilder filled(boolean fill) {
        styling.filled(fill);
        return this;
    }

    /**
     * Draw order; higher is drawn in front. The only way to control which of
     * several overlapping highlights is visible where they cross.
     */
    public DrawCaptionBuilder z(int order) {
        styling.z(order);
        return this;
    }

    /** Lifetime in milliseconds. Defaults to {@link DrawLimits#DEFAULT_TTL_MS}. */
    public DrawCaptionBuilder ttl(long milliseconds) {
        styling.ttl(milliseconds);
        return this;
    }

    /** Lifetime as a {@link Duration}, truncated to whole milliseconds. */
    public DrawCaptionBuilder ttl(Duration duration) {
        styling.ttl(duration);
        return this;
    }

    /** Keep until replaced, cleared, or the script's connection closes. */
    public DrawCaptionBuilder untilCleared() {
        styling.untilCleared();
        return this;
    }

    /** Client-area pixels, origin top-left. The default. */
    public DrawCaptionBuilder screen() {
        styling.screen();
        return this;
    }

    /** Game-world coordinates, projected by the producer. See {@link DrawSpace#WORLD}. */
    public DrawCaptionBuilder world() {
        styling.world();
        return this;
    }

    // --------------------------------------------------------------- terminals

    /**
     * The command as configured, without sending it.
     *
     * <p><b>The return type narrowed from {@link DrawCommand} to
     * {@link DrawCommand.Primitive}</b>, for the same reason and with the same
     * consequences as {@link DrawBuilder#build()}: source-compatible, not binary
     * compatible, so a script compiled against an older {@code bot-api} needs a
     * rebuild.</p>
     */
    public DrawCommand.Primitive build() {
        return factory.create(styling.currentSpace(), styling.currentStyle(), caption);
    }

    /** Send the command, and answer the key it is stored under. */
    public String submit() {
        return target.submit(build());
    }
}
