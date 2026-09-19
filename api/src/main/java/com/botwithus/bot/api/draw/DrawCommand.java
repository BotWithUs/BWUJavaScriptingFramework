package com.botwithus.bot.api.draw;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * One fully-specified draw command, ready for the wire.
 *
 * <p>Sealed, with one variant per geometry shape, so the encoder behind
 * {@link com.botwithus.bot.api.domain.DrawAPI} dispatches through an exhaustive
 * {@code switch} and a new primitive cannot be added without every encoder being
 * told about it.</p>
 *
 * <p><b>Two families, and which one a command belongs to decides which RPC carries
 * it.</b> That is the split {@link Primitive} and {@link Highlight} exist to make
 * structural rather than remembered:</p>
 *
 * <ul>
 *   <li>A {@link Primitive} names its own {@code kind} and {@code space} and rides
 *       {@code debug_draw_set} — or, many at a time, {@code debug_draw_set_batch}.</li>
 *   <li>A {@link Highlight} names neither. The producer forces both, because the
 *       thing being marked is an entity or a footprint rather than a rectangle, and
 *       it re-reads that thing's live position every tick. Each one rides its own
 *       {@code highlight_*} call. <b>A highlight cannot go in a batch</b>, and the
 *       type system is what enforces that — see {@link Highlight}.</li>
 * </ul>
 *
 * <p><b>Commands are keyed, not handled.</b> The key is a caller-chosen string of
 * 1 to {@link DrawLimits#MAX_KEY_BYTES} UTF-8 bytes; setting the same key again
 * replaces rather than appends, so redrawing {@code "target-box"} every tick is
 * idempotent. There is no handle to leak and nothing to free. Keys are scoped to
 * the connection: two scripts on two clients may both use {@code "target-box"}
 * without colliding, and the producer drops everything a connection drew when its
 * pipe closes.</p>
 */
public sealed interface DrawCommand {

    /** The caller-chosen identity of this command within its connection. */
    String key();

    /** The coordinate space this command's geometry is expressed in. */
    DrawSpace space();

    /** Colour, stroke, fill, order and lifetime. */
    DrawStyle style();

    /** The primitive this command draws. */
    DrawKind kind();

    /**
     * The key, validated.
     *
     * <p>Rejected here rather than on the wire so a script author gets the failure
     * at the call site with a stack trace, instead of a {@code dropped} count from
     * a batch that is already gone.</p>
     *
     * <p>The producer measures a key in UTF-8 bytes, so that is what is measured
     * here; {@code String.length()} would wave through keys the producer refuses.</p>
     */
    static String requireKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("a draw key must be a non-empty string");
        }
        int bytes = key.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > DrawLimits.MAX_KEY_BYTES) {
            throw new IllegalArgumentException("draw key exceeds "
                    + DrawLimits.MAX_KEY_BYTES + " UTF-8 bytes (" + bytes + "): " + key);
        }
        return key;
    }

    /**
     * A command {@code debug_draw_set} carries: the caller names its {@code kind}
     * and its {@code space}, and the producer stores the geometry as sent.
     *
     * <p>These are the only commands a batch can hold, and that is the whole reason
     * this type exists rather than being left implicit.
     * {@link com.botwithus.bot.api.domain.DrawAPI#drawSetBatch(java.util.List)}
     * takes {@code List<Primitive>}, so a {@link Highlight} in a batch is a
     * compile error rather than a producer-side {@code unknown kind} at run time.</p>
     */
    sealed interface Primitive extends DrawCommand
            permits Line, Rect, Ellipse, Text, Poly, ComponentTarget {
    }

    /**
     * A command only a {@code highlight_*} call can carry, because what it marks is
     * a thing rather than a place.
     *
     * <p>A {@link Primitive} stores the coordinates it was sent. A highlight stores
     * a <i>reference</i> — an entity's list slot, or a tile footprint on a named
     * plane — and the producer re-resolves it on the game thread every tick. That is
     * the difference that matters at the call site: a world-space {@link Rect} at an
     * NPC's tile and a highlight of that NPC look identical on the first frame and
     * diverge the moment the NPC walks.</p>
     *
     * <p><b>Neither {@code kind} nor {@code space} is a caller choice here.</b> The
     * handler forces both, so this API does not send either: {@link #space()} is
     * always {@link DrawSpace#WORLD} because there is no screen-space meaning for
     * "the npc with index 42", and {@link #kind()} reports what the producer will
     * store — which is what {@code debug_draw_list} reads back — rather than
     * something that could be put in a {@code kind} field. Both of those kinds are
     * deliberately unspellable on a set; see {@link DrawKind#isSpellable()}.</p>
     *
     * <p><b>Not batchable, structurally.</b> {@code debug_draw_set_batch} applies
     * {@code debug_draw_set} items and nothing else — its {@code ApplyBatchItem}
     * runs the ordinary {@code BuildSetRequest} path, which has no way to name a
     * semantic kind. So each highlight is its own round-trip. {@link DrawFrame}
     * still accepts them and still works; see {@link DrawFrame#flush()} for what
     * that costs and what it means when a flush fails part-way.</p>
     *
     * <p>Every one of them takes an optional caption, for the same reason a
     * component highlight does: the caller never learns where the marker landed, so
     * with six on screen a label is the only way to tell them apart. The producer
     * spells it {@code label} — {@code text} is refused — and {@link DrawCaption} is
     * what makes the wrong spelling unrepresentable rather than merely refused.</p>
     */
    sealed interface Highlight extends DrawCommand permits Entity, Tile, Area {

        /** The words drawn against the resolved marker, or {@link DrawCaption#NONE}. */
        DrawCaption caption();

        /**
         * Always {@link DrawSpace#WORLD}. Not a default the caller may override —
         * the handler forces it before validating anything, and the store refuses
         * the screen-space combination.
         */
        @Override
        default DrawSpace space() {
            return DrawSpace.WORLD;
        }

        /**
         * A tile coordinate or tile extent, validated against
         * {@link DrawLimits#MAX_WORLD_TILE}.
         *
         * <p>Rejected at the call site rather than on the wire, and the bound is the
         * one the producer's {@code TileToWire} applies before it multiplies by
         * {@link DrawLimits#SUBTILE_SCALE} — so this refuses exactly what the
         * producer would, one round-trip earlier.</p>
         */
        static int requireTile(int tile, String name) {
            if (tile < -DrawLimits.MAX_WORLD_TILE || tile > DrawLimits.MAX_WORLD_TILE) {
                throw new IllegalArgumentException(name + " must be within +/-"
                        + DrawLimits.MAX_WORLD_TILE + " tiles, got " + tile);
            }
            return tile;
        }

        /** A footprint side, in tiles: positive and inside the world. */
        static int requireExtentTiles(int tiles, String name) {
            if (tiles <= 0) {
                throw new IllegalArgumentException(
                        name + " must be a positive tile count, got " + tiles);
            }
            return requireTile(tiles, name);
        }

        /** A plane, {@link DrawLimits#MIN_PLANE} to {@link DrawLimits#MAX_PLANE}. */
        static int requirePlane(int plane) {
            if (plane < DrawLimits.MIN_PLANE || plane > DrawLimits.MAX_PLANE) {
                throw new IllegalArgumentException("plane must be "
                        + DrawLimits.MIN_PLANE + ".." + DrawLimits.MAX_PLANE
                        + ", got " + plane);
            }
            return plane;
        }

        /** The caption, validated. {@link DrawCaption#NONE} for no caption. */
        static DrawCaption requireCaption(DrawCaption caption) {
            if (caption == null) {
                throw new IllegalArgumentException(
                        "caption — use DrawCaption.NONE for an uncaptioned highlight");
            }
            return caption;
        }
    }

    /** Straight segment from {@code (x1, y1)} to {@code (x2, y2)}. */
    record Line(String key, DrawSpace space, DrawStyle style,
                int x1, int y1, int x2, int y2) implements Primitive {

        public Line {
            requireKey(key);
        }

        @Override
        public DrawKind kind() {
            return DrawKind.LINE;
        }
    }

    /** Axis-aligned rectangle, stroked or filled per {@link DrawStyle#isFilled()}. */
    record Rect(String key, DrawSpace space, DrawStyle style,
                int x, int y, int w, int h) implements Primitive {

        public Rect {
            requireKey(key);
        }

        @Override
        public DrawKind kind() {
            return DrawKind.RECT;
        }
    }

    /** Ellipse inscribed in the given rectangle, stroked or filled. */
    record Ellipse(String key, DrawSpace space, DrawStyle style,
                   int x, int y, int w, int h) implements Primitive {

        public Ellipse {
            requireKey(key);
        }

        @Override
        public DrawKind kind() {
            return DrawKind.ELLIPSE;
        }
    }

    /**
     * A caption anchored at {@code (x, y)}.
     *
     * <p>The caption is required here — a text command that draws nothing is not
     * a thing worth sending. It may be literal text or a fixed-point number; see
     * {@link DrawCaption}.</p>
     */
    record Text(String key, DrawSpace space, DrawStyle style,
                int x, int y, DrawCaption caption) implements Primitive {

        public Text {
            requireKey(key);
            if (caption == null || caption.isEmpty()) {
                throw new IllegalArgumentException("a text command needs a caption");
            }
        }

        @Override
        public DrawKind kind() {
            return DrawKind.TEXT;
        }
    }

    /** Polyline over a flat {@code [x, y, x, y, ...]} list of 2 to 32 pairs. */
    record Poly(String key, DrawSpace space, DrawStyle style,
                List<Integer> points) implements Primitive {

        private static final int VALUES_PER_POINT = 2;

        public Poly {
            requireKey(key);
            if (points == null || (points.size() % VALUES_PER_POINT) != 0) {
                throw new IllegalArgumentException(
                        "poly points must be a flat [x, y, x, y, ...] list");
            }
            int pairs = points.size() / VALUES_PER_POINT;
            if (pairs < DrawLimits.MIN_POLY_POINTS || pairs > DrawLimits.MAX_POLY_POINTS) {
                throw new IllegalArgumentException("poly needs "
                        + DrawLimits.MIN_POLY_POINTS + ".." + DrawLimits.MAX_POLY_POINTS
                        + " points, got " + pairs);
            }
            points = List.copyOf(points);
        }

        @Override
        public DrawKind kind() {
            return DrawKind.POLY;
        }
    }

    /**
     * A highlight that tracks an interface component rather than a rectangle.
     *
     * <p>Carries no geometry: the producer stores {@code (interfaceId, componentId)}
     * and re-resolves the on-screen rect on the game thread once per tick, because
     * the client recomputes a component's rect on every layout pass. A stored
     * rectangle would drift the moment the UI relaid out, the window resized, or a
     * scrollpane moved.</p>
     *
     * <p>The optional caption is the only way to tell highlights apart, and it has
     * to travel with the command precisely <i>because</i> the caller never learns
     * where the rect landed — with six highlights on screen there is nothing else
     * to distinguish them by. The producer draws it against the resolved rect.
     * {@link DrawCaption#NONE} for an uncaptioned highlight.</p>
     */
    record ComponentTarget(String key, DrawSpace space, DrawStyle style,
                           int interfaceId, int componentId,
                           DrawCaption caption) implements Primitive {

        /** Prefix of the conventional auto key, as the producer spells it. */
        private static final String AUTO_KEY_PREFIX = "comp:";

        public ComponentTarget {
            requireKey(key);
            if (caption == null) {
                throw new IllegalArgumentException(
                        "caption — use DrawCaption.NONE for an uncaptioned highlight");
            }
        }

        /**
         * The key a component highlight gets when the caller names none:
         * {@code comp:<iface>:<comp>}, for example {@code comp:1473:5}.
         *
         * <p>That format is part of the producer's contract, and it exists so a
         * caller who omitted a key still has a way to name the highlight in order
         * to clear it. This host computes it rather than reading it back, so the
         * key is known before the command is sent — but the producer echoes the
         * key it actually used on every reply, and a live test asserts the two
         * agree rather than assuming it.</p>
         */
        public static String autoKey(int interfaceId, int componentId) {
            return AUTO_KEY_PREFIX + interfaceId + ':' + componentId;
        }

        @Override
        public DrawKind kind() {
            return DrawKind.COMPONENT;
        }
    }

    /**
     * A highlight that <b>tracks a live entity</b> — an NPC, another player, or the
     * local player.
     *
     * <p>Carries no coordinates. The producer keeps {@code (ref, serverIndex)} and
     * re-reads the entity's position on the game thread every tick, so the box
     * follows a walking NPC instead of staying where the NPC was when the call was
     * made. It reads the <i>fractional</i> scene position rather than the truncated
     * tile, so it tracks smoothly rather than jumping a tile at a time, and the
     * footprint is centred on the entity.</p>
     *
     * <p><b>This is the one world kind with no height approximation in it, and that
     * is why it exists.</b> Every other world command is drawn at the local player's
     * own elevation, because that is the producer's only ground-height source — so a
     * command naming a different plane resolves {@code unavailable} rather than
     * being drawn convincingly wrong on the player's floor (see {@link Tile}). An
     * entity carries its own height, so an entity highlight needs no plane, is
     * exempt from that refusal, and is exact upstairs, on a slope and on a
     * staircase. It is the recommended way to mark anything that moves.</p>
     *
     * @param key          the command's identity within this connection
     * @param style        colour, stroke, fill, order and lifetime
     * @param ref          which list {@code serverIndex} indexes
     * @param serverIndex  the entity's server-side slot; normalised to {@code 0} for
     *                     {@link EntityRef#SELF}, which carries no index because the
     *                     producer re-resolves the local player's slot every tick
     * @param widthTiles   footprint width in <b>tiles</b>, not sub-tiles
     * @param heightTiles  footprint height in <b>tiles</b>
     * @param caption      the label drawn against the resolved box, or
     *                     {@link DrawCaption#NONE}
     */
    record Entity(String key, DrawStyle style, EntityRef ref, int serverIndex,
                  int widthTiles, int heightTiles, DrawCaption caption)
            implements Highlight {

        public Entity {
            requireKey(key);
            if (ref == null) {
                throw new IllegalArgumentException("ref — name npc, player or self");
            }
            if (ref.hasIndex() && serverIndex < 0) {
                throw new IllegalArgumentException(
                        "a " + ref.wireName() + " server index must be non-negative, got "
                                + serverIndex);
            }
            serverIndex = ref.hasIndex() ? serverIndex : 0;
            Highlight.requireExtentTiles(widthTiles, "footprint width");
            Highlight.requireExtentTiles(heightTiles, "footprint height");
            Highlight.requireCaption(caption);
        }

        /**
         * The key an entity highlight gets when the caller names none:
         * {@code npc:<index>}, {@code player:<index>}, or bare {@code self}.
         *
         * <p>Decimal, no padding, and <b>no trailing field</b> — the entity <i>is</i>
         * the identity. Two highlights on one NPC with different footprints or
         * colours are the same highlight restyled, and a key that replaces is the
         * right answer; {@code self} carries no index because the local player does
         * not have a stable one.</p>
         *
         * <p>That format is the producer's contract and it exists so a caller who
         * omitted a key still has a way to name the highlight in order to clear it.
         * Computed here rather than read back, so {@link DrawBuilder#submit()} can
         * answer the key before the round-trip — and asserted against the producer
         * by a live test rather than assumed.</p>
         */
        public static String autoKey(EntityRef ref, int serverIndex) {
            return ref.hasIndex() ? ref.wireName() + ':' + serverIndex : ref.wireName();
        }

        @Override
        public DrawKind kind() {
            return DrawKind.ENTITY;
        }
    }

    /**
     * A highlight over exactly one game tile, on a named plane.
     *
     * <p>Coordinates are whole <b>tiles</b>, not sub-tiles — the producer's
     * {@code highlight_tile} is where the two units meet, so nothing downstream of
     * it sees both. A world-space {@link Rect} at the same place takes
     * {@code tile * }{@link DrawLimits#SUBTILE_SCALE} instead.</p>
     *
     * <p><b>{@code plane} is honoured by refusal, not by lookup, and a caller needs
     * to know that.</b> The producer's only ground-height source is the local
     * player's own elevation, so a tile on any other plane resolves
     * {@code unavailable} — nothing is drawn, deliberately, rather than a marker
     * placed convincingly wrong on the player's floor. {@link Entity} is the
     * exemption, because an entity carries its own height. So a tile highlight one
     * floor up is accepted, stored, and invisible until the player is on that floor;
     * that is the producer's answer to an unanswerable question, not a fault in this
     * binding.</p>
     *
     * <p>Sends no {@code w}/{@code h}: {@code highlight_tile} <b>refuses</b> them
     * rather than dropping them, because a silently-ignored {@code w} is how a
     * caller ends up convinced a 5x5 highlight rendered as 1x1 because of a
     * projection bug. {@link Area} is the call that takes an extent.</p>
     *
     * @param key      the command's identity within this connection
     * @param style    colour, stroke, fill, order and lifetime
     * @param tileX    absolute world tile X
     * @param tileY    absolute world tile Y
     * @param plane    {@code 0..}{@link DrawLimits#MAX_PLANE}
     * @param caption  the label drawn against the resolved footprint, or
     *                 {@link DrawCaption#NONE}
     */
    record Tile(String key, DrawStyle style, int tileX, int tileY, int plane,
                DrawCaption caption) implements Highlight {

        /** Prefix of the conventional auto key, as the producer spells it. */
        private static final String AUTO_KEY_PREFIX = "tile:";

        public Tile {
            requireKey(key);
            Highlight.requireTile(tileX, "tileX");
            Highlight.requireTile(tileY, "tileY");
            Highlight.requirePlane(plane);
            Highlight.requireCaption(caption);
        }

        /**
         * The key a tile highlight gets when the caller names none:
         * {@code tile:<x>:<y>:<plane>}, for example {@code tile:3200:3200:0}.
         *
         * <p><b>The plane is in the key because it distinguishes two highlights.</b>
         * A key is an identity and a set under an existing key replaces it, so any
         * field the key omits is a field two highlights can disagree on while
         * colliding — and the loser vanishes with no error, because replacing is a
         * legitimate operation. The same x/y on two floors is two tiles. The
         * producer's first cut was {@code tile:<x>:<y>} and collided for exactly
         * that case.</p>
         */
        public static String autoKey(int tileX, int tileY, int plane) {
            return AUTO_KEY_PREFIX + tileX + ':' + tileY + ':' + plane;
        }

        @Override
        public DrawKind kind() {
            return DrawKind.TILE;
        }
    }

    /**
     * A highlight over a {@code widthTiles} by {@code heightTiles} block of ground,
     * anchored at its minimum corner.
     *
     * <p>Everything {@link Tile} says applies, including the plane-by-refusal rule:
     * an area on a floor the local player is not standing on resolves
     * {@code unavailable} and draws nothing.</p>
     *
     * <p>{@link #kind()} reports {@link DrawKind#TILE} rather than an {@code area}
     * of its own, because that is what the producer stores — {@code highlight_area}
     * and {@code highlight_tile} write one kind with different extents, so an area
     * reads back from {@code debug_draw_list} spelled {@code tile}. Reporting a kind
     * this host invented would make {@link DrawEntry#kind()} disagree with the
     * producer for the same drawing.</p>
     *
     * @param key          the command's identity within this connection
     * @param style        colour, stroke, fill, order and lifetime
     * @param tileX        minimum-corner world tile X
     * @param tileY        minimum-corner world tile Y
     * @param widthTiles   width in <b>tiles</b>
     * @param heightTiles  height in <b>tiles</b>
     * @param plane        {@code 0..}{@link DrawLimits#MAX_PLANE}
     * @param caption      the label drawn against the resolved footprint, or
     *                     {@link DrawCaption#NONE}
     */
    record Area(String key, DrawStyle style, int tileX, int tileY,
                int widthTiles, int heightTiles, int plane,
                DrawCaption caption) implements Highlight {

        /** Prefix of the conventional auto key, as the producer spells it. */
        private static final String AUTO_KEY_PREFIX = "area:";

        public Area {
            requireKey(key);
            Highlight.requireTile(tileX, "tileX");
            Highlight.requireTile(tileY, "tileY");
            Highlight.requireExtentTiles(widthTiles, "widthTiles");
            Highlight.requireExtentTiles(heightTiles, "heightTiles");
            Highlight.requirePlane(plane);
            Highlight.requireCaption(caption);
        }

        /**
         * The key an area highlight gets when the caller names none:
         * {@code area:<x>:<y>:<w>:<h>:<plane>}.
         *
         * <p><b>The extent is in the key for the same reason the plane is</b> — two
         * areas sharing a corner at different sizes are two different regions, and a
         * key that omitted the size would let the second silently replace the first.
         * Worst case is 32 bytes, inside {@link DrawLimits#MAX_KEY_BYTES}.</p>
         */
        public static String autoKey(int tileX, int tileY, int widthTiles, int heightTiles,
                                     int plane) {
            return AUTO_KEY_PREFIX + tileX + ':' + tileY + ':' + widthTiles + ':'
                    + heightTiles + ':' + plane;
        }

        @Override
        public DrawKind kind() {
            return DrawKind.TILE;
        }
    }
}
