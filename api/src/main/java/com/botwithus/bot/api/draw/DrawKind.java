package com.botwithus.bot.api.draw;

/**
 * The primitive shapes the producer's overlay can retain.
 *
 * <p>Mirrors {@code overlay::DrawKind} in {@code NXTLibrary/src/overlay/DrawTypes.h}
 * and the {@code kind} strings the {@code debug_draw_set} handler parses. The
 * wire spelling is lower-case; {@link #wireName()} is the only thing that should
 * ever be put on the wire.</p>
 */
public enum DrawKind {

    /** Straight segment between two points. */
    LINE("line"),
    /** Axis-aligned rectangle, stroked or filled. */
    RECT("rect"),
    /** Axis-aligned ellipse inscribed in a rectangle, stroked or filled. */
    ELLIPSE("ellipse"),
    /** Open or closed polyline of 2..32 points. */
    POLY("poly"),
    /** A UTF-8 string anchored at a point. */
    TEXT("text"),
    /**
     * An interface component, named by {@code (interfaceId, componentId)} and
     * carrying no geometry: the producer re-resolves the on-screen rect on the
     * game thread once per tick, because the client recomputes a component's
     * rect on every layout pass.
     */
    COMPONENT("component");

    private final String wireName;

    DrawKind(String wireName) {
        this.wireName = wireName;
    }

    /** The lower-case token this kind is spelled as in the {@code kind} field. */
    public String wireName() {
        return wireName;
    }

    /**
     * The kind a producer reply spelled, or {@code null} when the producer named
     * a kind this build does not know. A decoder must tolerate that rather than
     * throw: the producer is versioned separately and may grow a primitive.
     */
    public static DrawKind fromWireName(String name) {
        for (DrawKind kind : values()) {
            if (kind.wireName.equals(name)) {
                return kind;
            }
        }
        return null;
    }
}
