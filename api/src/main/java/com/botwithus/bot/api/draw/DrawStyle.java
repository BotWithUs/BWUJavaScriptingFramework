package com.botwithus.bot.api.draw;

/**
 * The presentation half of a draw command — everything that is not geometry.
 *
 * <p>Built by {@link DrawBuilder}; a script rarely constructs one directly.
 * {@link #ttlMs()} is always populated and always goes on the wire, so the
 * lifetime a command actually got is a value you can read back rather than an
 * invisible producer-side default.</p>
 *
 * @param color     packed {@code 0xAARRGGBB}; see {@link Colors}
 * @param thickness stroke width, {@link DrawLimits#MIN_THICKNESS} to {@link DrawLimits#MAX_THICKNESS}
 * @param isFilled  fill rather than stroke; meaningful for rect, ellipse and poly
 * @param isClosed  close the loop back to the first point; meaningful for poly only
 * @param z         draw order, higher in front
 * @param ttlMs     lifetime in milliseconds, or {@link DrawLimits#TTL_UNTIL_CLEARED}
 */
public record DrawStyle(int color, int thickness, boolean isFilled, boolean isClosed,
                        int z, long ttlMs) {

    /** The style a builder starts from: opaque green, hairline, the producer's default TTL. */
    public static final DrawStyle DEFAULT =
            new DrawStyle(Colors.GREEN, DrawLimits.MIN_THICKNESS, false, false, 0,
                    DrawLimits.DEFAULT_TTL_MS);

    public DrawStyle {
        if (thickness < DrawLimits.MIN_THICKNESS || thickness > DrawLimits.MAX_THICKNESS) {
            throw new IllegalArgumentException("thickness must be "
                    + DrawLimits.MIN_THICKNESS + ".." + DrawLimits.MAX_THICKNESS
                    + ", got " + thickness);
        }
        if (ttlMs < 0) {
            throw new IllegalArgumentException("ttlMs must be >= 0 ("
                    + DrawLimits.TTL_UNTIL_CLEARED + " means until cleared), got " + ttlMs);
        }
    }
}
