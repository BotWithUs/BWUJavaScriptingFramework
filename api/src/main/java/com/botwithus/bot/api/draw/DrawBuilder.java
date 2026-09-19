package com.botwithus.bot.api.draw;

import java.time.Duration;

/**
 * Fluent presentation chain for one draw command.
 *
 * <pre>{@code
 * draw.rect("target-box", x, y, w, h).color(Colors.GREEN).thickness(2).ttl(1200).submit();
 * }</pre>
 *
 * <p>Mutable and single-use by convention — {@link #submit()} may be called more
 * than once, and each call re-sends the same key, which the producer treats as a
 * replace. Nothing is put on the wire until {@link #submit()}.</p>
 *
 * <p>Obtained from a {@link DrawTarget}: either {@link Draw} (each submit is its
 * own round-trip) or {@link DrawFrame} (submits accumulate and leave as one
 * batch). The chain reads identically either way, which is the point.</p>
 */
public final class DrawBuilder {

    private final DrawTarget target;
    private final DrawCommandFactory factory;

    private DrawSpace space = DrawSpace.SCREEN;
    private int color = DrawStyle.DEFAULT.color();
    private int thickness = DrawStyle.DEFAULT.thickness();
    private boolean isFilled = DrawStyle.DEFAULT.isFilled();
    private boolean isClosed = DrawStyle.DEFAULT.isClosed();
    private int z = DrawStyle.DEFAULT.z();
    private long ttlMs = DrawStyle.DEFAULT.ttlMs();

    DrawBuilder(DrawTarget target, DrawCommandFactory factory) {
        this.target = target;
        this.factory = factory;
    }

    /**
     * A builder held only for its styling state, by {@link DrawCaptionBuilder}.
     *
     * <p>It never builds a command of its own — the caption builder owns that —
     * so it carries no factory, and {@link #build()} on it would be a programming
     * error rather than a caller's mistake. Sharing it is what keeps one
     * implementation of what {@code color} or {@code ttl} mean across both chains.</p>
     */
    static DrawBuilder styleOnly(DrawTarget target) {
        return new DrawBuilder(target, null);
    }

    /** Packed {@code 0xAARRGGBB}. See {@link Colors}. */
    public DrawBuilder color(int argb) {
        this.color = argb;
        return this;
    }

    /** The current colour at a different 0..255 alpha. */
    public DrawBuilder alpha(int alpha) {
        this.color = Colors.withAlpha(color, alpha);
        return this;
    }

    /** Stroke width, {@link DrawLimits#MIN_THICKNESS} to {@link DrawLimits#MAX_THICKNESS}. */
    public DrawBuilder thickness(int pixels) {
        this.thickness = pixels;
        return this;
    }

    /** Fill rather than stroke. Meaningful for rect, ellipse and poly. */
    public DrawBuilder filled() {
        return filled(true);
    }

    /** Fill or stroke explicitly. */
    public DrawBuilder filled(boolean fill) {
        this.isFilled = fill;
        return this;
    }

    /** Close a polyline back to its first point. Meaningful for poly only. */
    public DrawBuilder closed() {
        this.isClosed = true;
        return this;
    }

    /** Draw order; higher is drawn in front. */
    public DrawBuilder z(int order) {
        this.z = order;
        return this;
    }

    /**
     * Lifetime in milliseconds. The default is {@link DrawLimits#DEFAULT_TTL_MS}
     * and it is always sent explicitly, so {@link Draw#list()} reports the
     * lifetime this command actually got.
     */
    public DrawBuilder ttl(long milliseconds) {
        this.ttlMs = milliseconds;
        return this;
    }

    /** Lifetime as a {@link Duration}, truncated to whole milliseconds. */
    public DrawBuilder ttl(Duration duration) {
        return ttl(duration.toMillis());
    }

    /**
     * Keep this command until it is replaced, cleared, or the script's connection
     * closes. It is <b>not</b> a way to leave something on screen after the
     * script stops: ownership is connection-scoped and the producer drops
     * everything a connection drew when its pipe closes.
     */
    public DrawBuilder untilCleared() {
        return ttl(DrawLimits.TTL_UNTIL_CLEARED);
    }

    /** Client-area pixels, origin top-left. The default. */
    public DrawBuilder screen() {
        this.space = DrawSpace.SCREEN;
        return this;
    }

    /**
     * Game-world coordinates, projected to the screen by the producer. Bounded by
     * {@link DrawLimits#MAX_WORLD_COORDINATE} rather than the screen bound, and
     * refused for a {@link DrawKind#POLY}. See {@link DrawSpace#WORLD}.
     */
    public DrawBuilder world() {
        this.space = DrawSpace.WORLD;
        return this;
    }

    /** The space this builder will stamp on the command. Shared with {@link DrawCaptionBuilder}. */
    DrawSpace currentSpace() {
        return space;
    }

    /** The style this builder will stamp on the command. Shared with {@link DrawCaptionBuilder}. */
    DrawStyle currentStyle() {
        return new DrawStyle(color, thickness, isFilled, isClosed, z, ttlMs);
    }

    /**
     * The command as configured, without sending it.
     *
     * <p><b>The return type narrowed from {@link DrawCommand} to
     * {@link DrawCommand.Primitive}.</b> Source-compatible — a {@code Primitive} is a
     * {@code DrawCommand}, so every existing use still compiles — but not binary
     * compatible, so a script compiled against an older {@code bot-api} needs a rebuild.
     * The narrowing is what lets {@code drawSetBatch} take {@code List<Primitive>} and
     * so refuse a highlight at compile time; this builder never built anything else.</p>
     */
    public DrawCommand.Primitive build() {
        return factory.create(space, new DrawStyle(color, thickness, isFilled, isClosed, z, ttlMs));
    }

    /**
     * Send the command, and answer the key it is stored under — which is also how
     * you clear it later. Inside a {@link DrawFrame} this only queues; the wire
     * traffic happens when the frame is flushed or closed.
     *
     * <p>A command the producer refuses propagates the transport's own unchecked
     * RPC error when submitted through {@link Draw}, and is counted into
     * {@link DrawBatchResult#dropped()} when submitted through a
     * {@link DrawFrame}. The two failure shapes differ because the wire's do —
     * see {@link DrawFrame#flush()}.</p>
     */
    public String submit() {
        return target.submit(build());
    }
}
