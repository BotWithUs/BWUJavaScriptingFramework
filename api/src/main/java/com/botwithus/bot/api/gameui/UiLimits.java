package com.botwithus.bot.api.gameui;

/**
 * The agent's plugin-UI limits, mirrored from NXTLibrary
 * ({@code src/rpc/UiRegistry.h}, {@code src/rpc/UiHandlers.cpp},
 * {@code src/game/PluginUi.h}). Change one there and change it here in the same
 * logical change.
 *
 * <p>The agent enforces every one of these itself and its refusal reaches the
 * script verbatim, as a {@link UiRefusedException}. The host checks only the two
 * it has to act on: {@link #MAX_CANVAS_OPS}, which it splits a batch at, and the
 * category range, because category {@code 0} is one the host must never send.</p>
 */
public final class UiLimits {

    /** Ops per {@code ui.canvas} call. {@link CanvasBatch} splits at this. */
    public static final int MAX_CANVAS_OPS = 128;

    /** Elements one canvas holds; a batch that would pass it is refused before its first op. */
    public static final int MAX_CANVAS_ELEMENTS = 4096;

    /** Handles in one connection's table, Jagex handles included. Shared by every script on it. */
    public static final int MAX_HANDLES = 256;

    /** Nesting depth below a Jagex parent. */
    public static final int MAX_DEPTH = 8;

    /** Bytes of UTF-8 a text component holds; its text must be strictly shorter. */
    public static final int TEXT_CAPACITY_BYTES = 1024;

    /** Bound on {@code x}, {@code y} (either sign), {@code width} and {@code height}. */
    public static final int MAX_COORDINATE = 100_000;

    /** Bound on line widths, outline thickness and canvas op thickness. */
    public static final int MAX_THICKNESS = 1000;

    /** Bound on every {@code *_pc} value (either sign). */
    public static final double MAX_PERCENT = 10.0;

    /** Lowest category an agent component may use; {@code 0} is never sent. */
    public static final int MIN_CATEGORY = 1;

    /** Highest category an agent component may use. */
    public static final int MAX_CATEGORY = 234;

    /** Highest explicit dynamic id. */
    public static final int MAX_DYNAMIC = 0xFFF;

    private UiLimits() {
    }
}
