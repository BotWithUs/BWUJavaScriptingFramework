package com.botwithus.bot.api.draw;

/**
 * The coordinate space a draw command's geometry is expressed in.
 *
 * <p>{@link #SCREEN} is client-area pixels with the origin at the top left, and
 * is the only space the producer implements today. {@link #WORLD} is accepted,
 * validated and then <b>rejected</b> by the producer with
 * {@code "world space requires projection - not yet implemented"} until the
 * world&rarr;screen projection lands. The field is on the wire from the start
 * precisely so this API's shape does not change when it does.</p>
 */
public enum DrawSpace {

    /** Client-area pixels, origin top-left. The default, and the one that works. */
    SCREEN("screen"),
    /**
     * Game-world coordinates in fixed point ({@code tile * 256 + subtile} — see
     * {@link DrawLimits#SUBTILE_SCALE}). Every submit in this space fails today.
     */
    WORLD("world");

    private final String wireName;

    DrawSpace(String wireName) {
        this.wireName = wireName;
    }

    /** The lower-case token this space is spelled as in the {@code space} field. */
    public String wireName() {
        return wireName;
    }

    /** The space a producer reply spelled, defaulting to {@link #SCREEN}. */
    public static DrawSpace fromWireName(String name) {
        return WORLD.wireName.equals(name) ? WORLD : SCREEN;
    }
}
