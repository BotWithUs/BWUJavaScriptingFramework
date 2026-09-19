package com.botwithus.bot.api.draw;

/**
 * The coordinate space a draw command's geometry is expressed in.
 *
 * <p>{@link #SCREEN} is client-area pixels with the origin at the top left.
 * {@link #WORLD} is game-world coordinates, which the producer projects to the
 * screen on its own thread — so a world command follows the thing it marks as the
 * camera moves, instead of being pinned where the camera happened to be when it
 * was sent.</p>
 *
 * <p>Both work. Through phases 1 and 2 world space was accepted, validated and
 * then refused with {@code "world space requires projection - not yet implemented"};
 * the field shipped on the wire from the start precisely so this API's shape would
 * not have to change when projection landed, and it did not.</p>
 *
 * <p>Not every primitive projects. A {@link DrawKind#POLY} is refused in world
 * space — its points live in a side slot with no room for a projected copy — and a
 * {@link DrawKind#COMPONENT} is already on screen, so it has no world position at
 * all.</p>
 */
public enum DrawSpace {

    /** Client-area pixels, origin top-left. The default, and the one that works. */
    SCREEN("screen"),
    /**
     * Game-world coordinates in fixed point ({@code tile * 256 + subtile} — see
     * {@link DrawLimits#SUBTILE_SCALE}), bounded by
     * {@link DrawLimits#MAX_WORLD_COORDINATE} rather than by the screen bound.
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
