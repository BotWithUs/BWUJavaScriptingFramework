package com.botwithus.bot.api.draw;

/**
 * Packed {@code 0xAARRGGBB} colours for debug drawing, and the two helpers for
 * building one.
 *
 * <p>Every named constant is fully opaque. Blend by passing an explicit alpha
 * through {@link #withAlpha(int, int)} or {@link #argb(int, int, int, int)} —
 * a colour with a zero alpha draws nothing, which looks exactly like a broken
 * overlay.</p>
 */
public final class Colors {

    /** Fully opaque alpha, as it appears in the top byte of an ARGB value. */
    public static final int OPAQUE = 0xFF;

    private static final int ALPHA_SHIFT = 24;
    private static final int RED_SHIFT = 16;
    private static final int GREEN_SHIFT = 8;
    private static final int CHANNEL_MASK = 0xFF;
    private static final int RGB_MASK = 0x00FFFFFF;

    /** The producer's own default when a command omits {@code color}. */
    public static final int GREEN = 0xFF00FF00;
    public static final int RED = 0xFFFF0000;
    public static final int BLUE = 0xFF0000FF;
    public static final int CYAN = 0xFF00FFFF;
    public static final int MAGENTA = 0xFFFF00FF;
    public static final int YELLOW = 0xFFFFFF00;
    public static final int ORANGE = 0xFFFF8000;
    public static final int WHITE = 0xFFFFFFFF;
    public static final int BLACK = 0xFF000000;
    public static final int GREY = 0xFF808080;

    /**
     * Pack four 0..255 channels. Values outside that range are masked rather
     * than rejected, so an arithmetic overflow in a caller's colour ramp cannot
     * bleed into a neighbouring channel.
     */
    public static int argb(int alpha, int red, int green, int blue) {
        return ((alpha & CHANNEL_MASK) << ALPHA_SHIFT)
                | ((red & CHANNEL_MASK) << RED_SHIFT)
                | ((green & CHANNEL_MASK) << GREEN_SHIFT)
                | (blue & CHANNEL_MASK);
    }

    /** The same colour at a different 0..255 alpha. */
    public static int withAlpha(int argb, int alpha) {
        return ((alpha & CHANNEL_MASK) << ALPHA_SHIFT) | (argb & RGB_MASK);
    }

    /** The 0..255 alpha channel of a packed colour. */
    public static int alphaOf(int argb) {
        return (argb >>> ALPHA_SHIFT) & CHANNEL_MASK;
    }

    private Colors() {
    }
}
