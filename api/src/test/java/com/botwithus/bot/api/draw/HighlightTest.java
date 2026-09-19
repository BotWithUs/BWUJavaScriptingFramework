package com.botwithus.bot.api.draw;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four named highlights, as types.
 *
 * <p>Two things here are <b>wire contract</b> rather than this host's choice, and
 * both are asserted as literal strings on purpose: get either wrong and a caller who
 * omitted a key has no way to name the highlight again in order to clear it. The
 * producer echoes the key it used on every reply, and {@code LiveHighlightSmokeTest}
 * checks these same formats against it rather than assuming they agree — this class
 * pins the format, that one pins the agreement.</p>
 *
 * <p>The rest is the input contract checked at the call site, so a script author gets
 * the failure with a stack trace instead of a producer message after a round-trip.</p>
 */
class HighlightTest {

    private static final DrawStyle STYLE = DrawStyle.DEFAULT;
    private static final int NPC_INDEX = 42;
    private static final int TILE_X = 3200;
    private static final int TILE_Y = 3213;
    private static final int PLANE = 1;
    private static final int AREA_W = 5;
    private static final int AREA_H = 7;

    private static DrawCommand.Entity entity(EntityRef ref, int index) {
        return new DrawCommand.Entity("k", STYLE, ref, index,
                DrawLimits.DEFAULT_FOOTPRINT_TILES, DrawLimits.DEFAULT_FOOTPRINT_TILES,
                DrawCaption.NONE);
    }

    private static DrawCommand.Tile tile(int x, int y, int plane) {
        return new DrawCommand.Tile("k", STYLE, x, y, plane, DrawCaption.NONE);
    }

    // ------------------------------------------------------------- the auto keys

    /**
     * An entity key names the entity and <b>nothing else</b> — no trailing padding.
     *
     * <p>The producer's own comment above {@code Handle_HighlightEntity} still says
     * {@code npc:<index>:0}; the code and {@code PROTOCOL.md} say otherwise and are
     * what this follows. Two highlights on one NPC with different footprints or
     * colours are the same highlight restyled, so replacing is correct and there is
     * nothing else for the key to distinguish.</p>
     */
    @Test
    void entityAutoKey_namesTheEntityAndNothingElse() {
        assertAll(
                () -> assertEquals("npc:42",
                        DrawCommand.Entity.autoKey(EntityRef.NPC, NPC_INDEX)),
                () -> assertEquals("player:42",
                        DrawCommand.Entity.autoKey(EntityRef.PLAYER, NPC_INDEX)),
                // `self` carries no index at all, because the local player does not
                // have a stable one — the producer re-resolves the slot every tick.
                () -> assertEquals("self",
                        DrawCommand.Entity.autoKey(EntityRef.SELF, NPC_INDEX)));
    }

    /**
     * A tile key carries its plane and an area key carries its extent, because a key
     * is an identity and a set under an existing key <i>replaces</i> it.
     *
     * <p>Any distinguishing field the key omits is a field two highlights can
     * disagree on while colliding, and the loser vanishes with no error. The
     * producer's first cut was {@code tile:<x>:<y>}, which collided for the same x/y
     * on two floors.</p>
     */
    @Test
    void tileAndAreaAutoKeys_carryEveryDistinguishingField() {
        assertAll(
                () -> assertEquals("tile:3200:3213:1",
                        DrawCommand.Tile.autoKey(TILE_X, TILE_Y, PLANE)),
                () -> assertEquals("area:3200:3213:5:7:1",
                        DrawCommand.Area.autoKey(TILE_X, TILE_Y, AREA_W, AREA_H, PLANE)));
    }

    /**
     * The producer's stated worst-case area key fits its 47-byte key buffer.
     *
     * <p>Asserted rather than trusted: the key is built from five signed decimal
     * fields, and the host computes it, so an over-long one would be refused by the
     * producer as {@code draw key must be 1..47 bytes} after the round-trip. The
     * record's own constructor is what catches it, which is why this asserts no throw
     * rather than a length.</p>
     */
    @Test
    void theWidestAreaAutoKey_fitsTheProducersKeyBuffer() {
        int widest = DrawLimits.MAX_WORLD_TILE;
        String key = DrawCommand.Area.autoKey(-widest, -widest, widest, widest,
                DrawLimits.MAX_PLANE);
        assertAll(
                () -> assertTrue(key.length() <= DrawLimits.MAX_KEY_BYTES,
                        () -> "worst-case area key is " + key.length() + " bytes: " + key),
                () -> assertEquals(key, DrawCommand.requireKey(key)));
    }

    // --------------------------------------------------------- kind and space

    /**
     * {@code space} is not a caller choice on any highlight: the handler forces
     * {@code world} before it validates anything.
     */
    @Test
    void everyHighlight_isWorldSpace() {
        List<DrawCommand.Highlight> all = List.of(
                entity(EntityRef.NPC, NPC_INDEX),
                tile(TILE_X, TILE_Y, PLANE),
                new DrawCommand.Area("k", STYLE, TILE_X, TILE_Y, AREA_W, AREA_H, PLANE,
                        DrawCaption.NONE));
        for (DrawCommand.Highlight highlight : all) {
            assertSame(DrawSpace.WORLD, highlight.space(),
                    () -> "not world space: " + highlight);
        }
    }

    /**
     * An area reports {@link DrawKind#TILE}, not a kind of its own.
     *
     * <p>{@code highlight_area} and {@code highlight_tile} are two methods over one
     * producer-side kind, so an area reads back from {@code debug_draw_list} spelled
     * {@code tile}. Inventing an {@code area} kind here would make
     * {@link DrawEntry#kind()} disagree with the producer for the same drawing — and
     * it is also why the method cannot be derived from the kind.</p>
     */
    @Test
    void areaAndTile_shareOneProducerKind() {
        assertAll(
                () -> assertSame(DrawKind.TILE, tile(TILE_X, TILE_Y, PLANE).kind()),
                () -> assertSame(DrawKind.TILE,
                        new DrawCommand.Area("k", STYLE, TILE_X, TILE_Y, AREA_W, AREA_H,
                                PLANE, DrawCaption.NONE).kind()),
                () -> assertSame(DrawKind.ENTITY, entity(EntityRef.NPC, NPC_INDEX).kind()));
    }

    /**
     * Both semantic kinds decode from a listed row and neither is spellable on a set.
     *
     * <p>Before they existed here, a highlight listed by {@code debug_draw_list} —
     * one this host drew, or one NXTDebugger drew on the same client — decoded to a
     * {@code null} kind, because {@code fromWireName} knew only the six a caller can
     * send. That is the hole this closes; {@link DrawKind#isSpellable()} is what keeps
     * closing it from also implying they can be sent.</p>
     */
    @Test
    void semanticKinds_decodeFromTheWireButCannotBeSent() {
        assertAll(
                () -> assertSame(DrawKind.ENTITY, DrawKind.fromWireName("entity")),
                () -> assertSame(DrawKind.TILE, DrawKind.fromWireName("tile")),
                () -> assertFalse(DrawKind.ENTITY.isSpellable()),
                () -> assertFalse(DrawKind.TILE.isSpellable()),
                () -> assertTrue(DrawKind.RECT.isSpellable()),
                () -> assertTrue(DrawKind.COMPONENT.isSpellable()));
    }

    // ------------------------------------------------------- the input contract

    /**
     * {@link EntityRef#SELF} stores no index, whatever it was handed.
     *
     * <p>Normalised rather than rejected because the three entity helpers funnel
     * through one general form and {@code self()} has no index to pass it. Storing
     * whatever arrived would put a meaningless number in a record that is compared by
     * value, so two {@code self} highlights built through different paths would not be
     * equal.</p>
     */
    @Test
    void self_storesNoIndexWhateverItWasGiven() {
        assertAll(
                () -> assertEquals(0, entity(EntityRef.SELF, NPC_INDEX).serverIndex()),
                () -> assertEquals(entity(EntityRef.SELF, 0), entity(EntityRef.SELF, NPC_INDEX)));
    }

    /** A negative index is refused for a list that has one, and ignored for {@code self}. */
    @Test
    void negativeServerIndex_isRefusedForAListThatHasOne() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> entity(EntityRef.NPC, -1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> entity(EntityRef.PLAYER, -1)),
                () -> assertEquals(0, entity(EntityRef.SELF, -1).serverIndex()));
    }

    /**
     * A footprint is a positive tile count. The producer refuses {@code <= 0} with
     * {@code entity footprint w and h must be positive tile counts}; this refuses it
     * a round-trip earlier.
     */
    @Test
    void nonPositiveFootprint_isRefused() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DrawCommand.Entity("k", STYLE, EntityRef.NPC, NPC_INDEX,
                                0, 1, DrawCaption.NONE)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DrawCommand.Entity("k", STYLE, EntityRef.NPC, NPC_INDEX,
                                1, -1, DrawCaption.NONE)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DrawCommand.Area("k", STYLE, TILE_X, TILE_Y, 0, AREA_H,
                                PLANE, DrawCaption.NONE)));
    }

    /**
     * A plane outside {@code 0..3} is refused.
     *
     * <p>Worth stating what this does <b>not</b> claim: an in-range plane other than
     * the one the local player stands on is accepted here and by the producer, and
     * then draws nothing — it resolves {@code unavailable}, because the producer's
     * only ground-height source is the player's own elevation. That is a runtime
     * outcome no constructor can see, and it is why entity highlights, which carry
     * their own height, are the ones to reach for.</p>
     */
    @Test
    void planeOutsideTheClientsRange_isRefused() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> tile(TILE_X, TILE_Y, -1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> tile(TILE_X, TILE_Y, DrawLimits.MAX_PLANE + 1)),
                () -> assertEquals(DrawLimits.MAX_PLANE,
                        tile(TILE_X, TILE_Y, DrawLimits.MAX_PLANE).plane()));
    }

    /**
     * Tile coordinates are bounded in <b>tiles</b>, which is the bound the producer's
     * {@code TileToWire} applies before multiplying by
     * {@link DrawLimits#SUBTILE_SCALE}.
     *
     * <p>The unit is the point. {@link DrawLimits#MAX_WORLD_COORDINATE} bounds a
     * {@code space: "world"} primitive's sub-tiles and is 256 times larger; bounding a
     * tile helper by that number would accept a tile the producer refuses, and
     * bounding a world primitive by this one would refuse anything past tile 128.</p>
     */
    @Test
    void tileCoordinates_areBoundedInTilesNotSubTiles() {
        int justOver = DrawLimits.MAX_WORLD_TILE + 1;
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> tile(justOver, TILE_Y, PLANE)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> tile(TILE_X, -justOver, PLANE)),
                () -> assertEquals(DrawLimits.MAX_WORLD_TILE,
                        tile(DrawLimits.MAX_WORLD_TILE, TILE_Y, PLANE).tileX()),
                // The two bounds are not the same number and must not be confused.
                () -> assertEquals(DrawLimits.MAX_WORLD_COORDINATE,
                        DrawLimits.MAX_WORLD_TILE * DrawLimits.SUBTILE_SCALE));
    }

    /** A null caption is refused; {@link DrawCaption#NONE} is how you say "no label". */
    @Test
    void nullCaption_isRefusedRatherThanTreatedAsAbsent() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DrawCommand.Entity("k", STYLE, EntityRef.NPC, NPC_INDEX,
                                1, 1, null)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DrawCommand.Tile("k", STYLE, TILE_X, TILE_Y, PLANE, null)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DrawCommand.Area("k", STYLE, TILE_X, TILE_Y, AREA_W,
                                AREA_H, PLANE, null)));
    }

    /** A null entity reference names no list, which an index alone cannot substitute for. */
    @Test
    void nullEntityRef_isRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new DrawCommand.Entity("k", STYLE, null, NPC_INDEX, 1, 1,
                        DrawCaption.NONE));
    }

    /**
     * Every highlight carries the key contract the primitives do — checked here
     * because each record validates its own, and a copy-paste that dropped the call
     * would otherwise only surface as a producer refusal.
     */
    @Test
    void everyHighlight_validatesItsKey() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DrawCommand.Entity("", STYLE, EntityRef.NPC, NPC_INDEX,
                                1, 1, DrawCaption.NONE)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DrawCommand.Tile("", STYLE, TILE_X, TILE_Y, PLANE,
                                DrawCaption.NONE)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DrawCommand.Area("", STYLE, TILE_X, TILE_Y, AREA_W,
                                AREA_H, PLANE, DrawCaption.NONE)));
    }
}
