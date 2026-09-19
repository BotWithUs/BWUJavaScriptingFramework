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
    COMPONENT("component"),
    /**
     * A tracked entity — an NPC, another player, or the local player — named by
     * {@link EntityRef} plus a server index and carrying no coordinates: the
     * producer re-reads the entity's live position every tick.
     *
     * <p><b>Decode-only.</b> See {@link #isSpellable()}: this kind is reachable
     * through {@code highlight_entity} and nowhere else.</p>
     */
    ENTITY("entity", false),
    /**
     * A ground footprint on one plane, named in whole tiles. Produced by both
     * {@code highlight_tile} and {@code highlight_area} — the producer stores one
     * kind for the two calls, so an area comes back spelled {@code tile} with a
     * bigger extent rather than as a kind of its own.
     *
     * <p><b>Decode-only.</b> See {@link #isSpellable()}.</p>
     */
    TILE("tile", false);

    private final String wireName;
    private final boolean isSpellable;

    DrawKind(String wireName) {
        this(wireName, true);
    }

    DrawKind(String wireName, boolean isSpellable) {
        this.wireName = wireName;
        this.isSpellable = isSpellable;
    }

    /** The lower-case token this kind is spelled as in the {@code kind} field. */
    public String wireName() {
        return wireName;
    }

    /**
     * Whether {@code debug_draw_set} accepts this kind in its {@code kind} field.
     *
     * <p>False for {@link #ENTITY} and {@link #TILE}, and that asymmetry is the
     * producer's, not this host's: both appear in {@code debug_draw_list} output —
     * which is why they exist here at all, so a listed highlight decodes to a kind
     * rather than to {@code null} — but {@code ParseDrawKind} deliberately omits
     * them, and setting one is refused with the ordinary {@code unknown kind}
     * error. They carry semantics no geometry key can express, so they are reached
     * only through {@code highlight_entity}, {@code highlight_tile} and
     * {@code highlight_area}, where every parameter is named for what it means and
     * a wrong one is reported by name. Spelling them on a set would accept
     * {@code {kind: "entity", x: 3, y: 4}} and draw something nobody asked for.</p>
     *
     * <p>Nothing in this host needs to consult it to stay correct — a
     * {@link DrawCommand.Highlight} is not a {@link DrawCommand.Primitive}, so the
     * encoder cannot put either of these on a set in the first place. It is here
     * for a reader of a {@link DrawEntry} who wants to know why a kind they can see
     * is one they cannot send.</p>
     */
    public boolean isSpellable() {
        return isSpellable;
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
