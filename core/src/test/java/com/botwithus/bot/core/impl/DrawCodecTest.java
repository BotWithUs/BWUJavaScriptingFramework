package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.draw.DrawCaption;
import com.botwithus.bot.api.draw.DrawCommand;
import com.botwithus.bot.api.draw.DrawFont;
import com.botwithus.bot.api.draw.DrawKind;
import com.botwithus.bot.api.draw.DrawSpace;
import com.botwithus.bot.api.draw.DrawStyle;
import com.botwithus.bot.api.draw.EntityRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Why a batch this host sends cannot be rejected for its content.
 *
 * <p>This matters because of an asymmetry in how {@code debug_draw_set_batch}
 * fails. The producer applies each item to its store <i>as it walks the array</i>
 * ({@code ApplyBatchItem}), so the two error paths leave very different state
 * behind:</p>
 *
 * <ul>
 *   <li>An {@code items} array longer than the cap is refused <b>before</b> the
 *       loop — nothing was applied, and the store is clean.</li>
 *   <li>A <b>structurally malformed item</b> at index N aborts the whole call
 *       with an envelope error, but items {@code 0..N-1} are <b>already in the
 *       store</b>, and the reply carries no indication of how far it got. The
 *       {@code count}/{@code dropped} the producer accumulated are discarded
 *       with the error.</li>
 * </ul>
 *
 * <p>The second path is the dangerous one, and the right answer is to make it
 * unreachable rather than to handle it. {@code ForEachParam} (MsgPack.h) returns
 * false — the only thing that sets the producer's {@code isMalformed} flag from
 * inside the loop — on exactly three things: an item that is not a msgpack map, a
 * key that is not a string, or a value that cannot be skipped. Everything else is
 * tolerated: an <b>unknown key is skipped</b>, and every value-type mismatch is a
 * soft per-item refusal that comes back in {@code dropped} rather than aborting
 * the call.</p>
 *
 * <p>{@link DrawCommand} is sealed and {@link DrawCodec#encode} switches over it
 * exhaustively into a {@code Map<String, Object>} with string keys and non-null
 * values. There is no input to this encoder that produces a non-map, a non-string
 * key, or a null. The tests below pin those three properties for every variant,
 * so the reachability argument is checked by the build rather than asserted in a
 * comment.</p>
 */
class DrawCodecTest {

    /**
     * Every key {@code MatchDrawParam} in the producer's {@code Handlers.cpp}
     * recognises. An encoded key outside this set would be <i>skipped</i> rather
     * than rejected, so this is a lockstep guard against silently sending a field
     * the producer ignores — not part of the malformed-item argument.
     */
    private static final Set<String> PRODUCER_KEYS = Set.of(
            "x", "y", "w", "h", "width", "height",
            "x1", "y1", "x2", "y2",
            "iface", "comp",
            "color", "thickness", "z", "ttl_ms",
            "key", "kind", "space",
            "filled", "closed",
            "text", "points",
            "label", "value", "decimals", "font",
            // The highlight_* parameters. Same table, same handler — the semantic
            // calls reuse MatchDrawParam wholesale rather than parsing their own.
            "plane", "npc", "player", "self");

    private static final DrawStyle STYLE = DrawStyle.DEFAULT;

    /** One of every sealed variant, including a world-space one. */
    private static List<DrawCommand.Primitive> everyPrimitive() {
        return List.of(
                new DrawCommand.Line("k-line", DrawSpace.SCREEN, STYLE, 1, 2, 3, 4),
                new DrawCommand.Rect("k-rect", DrawSpace.SCREEN, STYLE, 1, 2, 3, 4),
                new DrawCommand.Ellipse("k-ellipse", DrawSpace.SCREEN, STYLE, 1, 2, 3, 4),
                new DrawCommand.Text("k-text", DrawSpace.SCREEN, STYLE, 1, 2,
                        DrawCaption.of("hello")),
                new DrawCommand.Text("k-value", DrawSpace.SCREEN, STYLE, 1, 2,
                        DrawCaption.of(1234, 2, DrawFont.LARGE)),
                new DrawCommand.Poly("k-poly", DrawSpace.SCREEN, STYLE, List.of(1, 2, 3, 4)),
                new DrawCommand.ComponentTarget("k-comp", DrawSpace.SCREEN, STYLE, 1473, 5,
                        DrawCaption.NONE),
                new DrawCommand.ComponentTarget("k-labelled", DrawSpace.SCREEN, STYLE, 1473, 5,
                        DrawCaption.of("Inventory", DrawFont.HEADING)),
                new DrawCommand.Rect("k-world", DrawSpace.WORLD, STYLE, 1, 2, 3, 4));
    }

    /**
     * The first of the three things that can abort a batch mid-walk. Every variant
     * encodes to a map, so no item can fail {@code ReadMapHeader}.
     */
    @Test
    void everyVariant_encodesToANonEmptyMap() {
        for (DrawCommand.Primitive command : everyPrimitive()) {
            Map<String, Object> encoded = DrawCodec.encode(command);
            assertAll(command.key(),
                    () -> assertNotNull(encoded),
                    () -> assertFalse(encoded.isEmpty()));
        }
    }

    /**
     * The second. Keys come from string literals in the encoder, never from
     * caller data, so no item can fail the key-side {@code ReadString}.
     */
    @Test
    void everyVariant_encodesOnlyNonEmptyStringKeys() {
        for (DrawCommand.Primitive command : everyPrimitive()) {
            for (String key : DrawCodec.encode(command).keySet()) {
                assertAll(command.key() + " -> " + key,
                        () -> assertNotNull(key),
                        () -> assertFalse(key.isEmpty()));
            }
        }
    }

    /**
     * The third. A null value would encode as msgpack nil; nil is skippable, so it
     * would be a soft refusal rather than an abort — but it would also be a silent
     * one, and nothing about a sealed command's accessors can produce it. Pinned
     * because {@code encode} builds a {@link java.util.LinkedHashMap}, which
     * unlike {@code Map.of} would happily accept one.
     */
    @Test
    void everyVariant_encodesNoNullValues() {
        for (DrawCommand.Primitive command : everyPrimitive()) {
            DrawCodec.encode(command).forEach((key, value) ->
                    assertNotNull(value, () -> command.key() + " encoded a null " + key));
        }
    }

    /**
     * Lockstep guard: a field this host sends that the producer does not parse is
     * silently dropped on the floor, which is exactly the kind of drift that looks
     * like it works until someone reads a {@code debug_draw_list} row.
     */
    @Test
    void everyVariant_encodesOnlyKeysTheProducerParses() {
        for (DrawCommand.Primitive command : everyPrimitive()) {
            for (String key : DrawCodec.encode(command).keySet()) {
                assertTrue(PRODUCER_KEYS.contains(key),
                        () -> command.kind() + " encodes \"" + key
                                + "\", which MatchDrawParam does not read");
            }
        }
    }

    /**
     * A shape must never carry a caption or a font.
     *
     * <p>The producer rejects both on a {@code rect}, {@code ellipse}, {@code line}
     * or {@code poly} — {@code only text and component commands take text, label or
     * value} and {@code only text and component commands take a font} — and its own
     * spec tells callers who build commands from a shared style object to strip
     * {@code font} for the shapes.</p>
     *
     * <p>This host has nothing to strip, and that is the point: {@code font} lives
     * on {@link DrawCaption}, a caption lives only on the two captioned variants,
     * and shapes have no field for either. This test is the guard on that placement
     * — put {@code font} on {@link DrawStyle} for convenience and it fails here
     * rather than in a scripter's dropped count.</p>
     */
    @Test
    void shapes_encodeNoCaptionAndNoFont() {
        Set<String> captionKeys = Set.of("text", "label", "value", "decimals", "font");
        for (DrawCommand.Primitive command : everyPrimitive()) {
            if (command.kind() == DrawKind.TEXT || command.kind() == DrawKind.COMPONENT) {
                continue;
            }
            for (String key : DrawCodec.encode(command).keySet()) {
                assertFalse(captionKeys.contains(key),
                        () -> command.kind() + " encodes \"" + key
                                + "\", which the producer refuses on a shape");
            }
        }
    }

    /** The captioned kinds use the spelling their own kind accepts, and no other. */
    @Test
    void captionedKinds_useTheSpellingTheirKindAccepts() {
        Map<String, Object> literalText = DrawCodec.encode(
                new DrawCommand.Text("k", DrawSpace.SCREEN, STYLE, 0, 0,
                        DrawCaption.of("hi", DrawFont.SMALL)));
        Map<String, Object> labelled = DrawCodec.encode(
                new DrawCommand.ComponentTarget("k", DrawSpace.SCREEN, STYLE, 1, 2,
                        DrawCaption.of("Inventory")));
        Map<String, Object> number = DrawCodec.encode(
                new DrawCommand.Text("k", DrawSpace.SCREEN, STYLE, 0, 0,
                        DrawCaption.of(1234, 2)));
        Map<String, Object> bare = DrawCodec.encode(
                new DrawCommand.ComponentTarget("k", DrawSpace.SCREEN, STYLE, 1, 2,
                        DrawCaption.NONE));

        assertAll(
                // A text command says "text"; sending "label" would be refused.
                () -> assertEquals("hi", literalText.get("text")),
                () -> assertEquals("small", literalText.get("font")),
                () -> assertFalse(literalText.containsKey("label")),
                // A component says "label"; sending "text" is an explicit producer error.
                () -> assertEquals("Inventory", labelled.get("label")),
                () -> assertFalse(labelled.containsKey("text")),
                // A number is the third spelling and excludes the other two.
                () -> assertEquals(1234L, number.get("value")),
                () -> assertEquals(2, number.get("decimals")),
                () -> assertFalse(number.containsKey("text")),
                () -> assertFalse(number.containsKey("label")),
                // No caption means no payload and no font at all.
                () -> assertFalse(bare.containsKey("label")),
                () -> assertFalse(bare.containsKey("font")),
                () -> assertFalse(bare.containsKey("value")));
    }

    /**
     * Value types the producer's matchers accept. A mismatch here would be a soft
     * per-item refusal — visible in {@code dropped} rather than fatal — but it
     * would still mean the command never drew.
     */
    @Test
    void everyVariant_encodesTheValueTypesTheProducerReads() {
        for (DrawCommand.Primitive command : everyPrimitive()) {
            DrawCodec.encode(command).forEach((key, value) ->
                    assertTrue(isExpectedType(key, value),
                            () -> command.kind() + " encodes " + key + " as "
                                    + value.getClass().getSimpleName()));
        }
    }

    // rule-exception: {rule:no-instanceof} — asserting the wire type of an
    // encoded value is the one thing this test exists to do, and the encoded map
    // is Map<String, Object> by the transport's own signature. Same justification
    // as the decode-boundary waiver on RpcClient.callSync, and confined here.
    private static boolean isExpectedType(String key, Object value) {
        return switch (key) {
            case "key", "kind", "space", "text", "label", "font" -> value instanceof String;
            // "self" is a bool on the producer's table, not an index. It is the one
            // entity spelling that carries no number, because the local player has no
            // stable slot.
            case "filled", "closed", "self" -> value instanceof Boolean;
            case "points" -> value instanceof List<?>;
            default -> value instanceof Number;
        };
    }

    /**
     * The three {@code highlight_*} encodings.
     *
     * <p>These go through a different function from {@link DrawCodec#encode} and a
     * different handler, so the properties the tests above pin for a primitive have to
     * be pinned again here rather than inherited. The interesting assertions are the
     * <b>absences</b>: a highlight must not send a {@code kind}, a {@code space}, or —
     * for an entity — a {@code plane}, because each of those would be a parameter the
     * producer discards or a choice the caller does not actually have.</p>
     */
    @Nested
    @DisplayName("highlight encoding")
    class Highlights {

        private static final int NPC_INDEX = 42;
        private static final int TILE_X = 3200;
        private static final int TILE_Y = 3213;
        private static final int PLANE = 2;

        private DrawCommand.Entity entity(EntityRef ref) {
            return new DrawCommand.Entity("k", STYLE, ref, NPC_INDEX, 3, 4, DrawCaption.NONE);
        }

        private List<DrawCommand.Highlight> everyHighlight() {
            return List.of(
                    entity(EntityRef.NPC),
                    entity(EntityRef.PLAYER),
                    entity(EntityRef.SELF),
                    new DrawCommand.Tile("k", STYLE, TILE_X, TILE_Y, PLANE, DrawCaption.NONE),
                    new DrawCommand.Tile("k", STYLE, TILE_X, TILE_Y, PLANE,
                            DrawCaption.of("here")),
                    new DrawCommand.Area("k", STYLE, TILE_X, TILE_Y, 5, 7, PLANE,
                            DrawCaption.of(1234, 2)));
        }

        /**
         * Each variant reaches the method that can carry it.
         *
         * <p>{@code highlight_tile} and {@code highlight_area} are two methods over one
         * producer-side kind, so this mapping genuinely cannot be derived from
         * {@link DrawCommand#kind()} — which is why it is a switch over the variant and
         * why it is worth asserting.</p>
         */
        @Test
        void eachVariant_ridesItsOwnMethod() {
            assertAll(
                    () -> assertEquals("highlight_entity",
                            DrawCodec.highlightMethod(entity(EntityRef.NPC))),
                    () -> assertEquals("highlight_tile", DrawCodec.highlightMethod(
                            new DrawCommand.Tile("k", STYLE, TILE_X, TILE_Y, PLANE,
                                    DrawCaption.NONE))),
                    () -> assertEquals("highlight_area", DrawCodec.highlightMethod(
                            new DrawCommand.Area("k", STYLE, TILE_X, TILE_Y, 5, 7, PLANE,
                                    DrawCaption.NONE))));
        }

        /**
         * No {@code kind} and no {@code space} on any highlight.
         *
         * <p>The handler forces both — it sets the kind itself because {@code entity}
         * and {@code tile} are not spellable, and overwrites {@code space} with
         * {@code world} before validating anything. Sending them would be discarded
         * noise, and a {@code space} in particular would read like a choice the caller
         * has. This is also the guard against the obvious lazy implementation: reusing
         * {@link DrawCodec#encode} for a highlight would put {@code kind: "entity"} on
         * the wire, which {@code debug_draw_set} refuses outright.</p>
         */
        @Test
        void noHighlight_sendsAKindOrASpace() {
            for (DrawCommand.Highlight highlight : everyHighlight()) {
                Map<String, Object> encoded = DrawCodec.encodeHighlight(highlight);
                assertAll(
                        () -> assertFalse(encoded.containsKey("kind"),
                                () -> highlight + " sends a kind the handler forces"),
                        () -> assertFalse(encoded.containsKey("space"),
                                () -> highlight + " sends a space the handler forces"),
                        () -> assertFalse(encoded.containsKey("closed"),
                                () -> highlight + " sends a polyline flag"));
            }
        }

        /**
         * An entity names exactly one list, sends its footprint in tiles, and sends no
         * plane.
         *
         * <p>The plane omission is deliberate and is the one thing here a reader might
         * mistake for an oversight: an entity is the single world kind that carries its
         * own height, so the producer resolves it from the entity's live scene position
         * and never reads {@code plane}. Sending one would be accepted and ignored,
         * which is the exact mistake this wire's design keeps refusing to make.</p>
         */
        @Test
        void anEntity_namesOneListAndSendsNoPlane() {
            Map<String, Object> npc = DrawCodec.encodeHighlight(entity(EntityRef.NPC));
            Map<String, Object> player = DrawCodec.encodeHighlight(entity(EntityRef.PLAYER));
            Map<String, Object> self = DrawCodec.encodeHighlight(entity(EntityRef.SELF));

            assertAll(
                    () -> assertEquals(NPC_INDEX, npc.get("npc")),
                    () -> assertFalse(npc.containsKey("player")),
                    () -> assertFalse(npc.containsKey("self")),
                    () -> assertEquals(NPC_INDEX, player.get("player")),
                    () -> assertFalse(player.containsKey("npc")),
                    // self is a bool and carries no index; sending self:false would be
                    // counted as a named reference and collide rather than be ignored.
                    () -> assertEquals(Boolean.TRUE, self.get("self")),
                    () -> assertFalse(self.containsKey("npc")),
                    () -> assertFalse(self.containsKey("player")),
                    // Footprint in TILES, as the handler reads it.
                    () -> assertEquals(3, npc.get("w")),
                    () -> assertEquals(4, npc.get("h")),
                    () -> assertFalse(npc.containsKey("plane"),
                            "an entity carries its own height and never reads plane"));
        }

        /**
         * A tile sends a plane and <b>no extent</b>; an area sends both.
         *
         * <p>{@code highlight_tile} refuses a {@code w} rather than dropping it, so an
         * extent leaking onto a tile would be a hard refusal rather than a bigger box.
         * That refusal is unreachable from this API because the two are different
         * records with different fields, and this is the guard on that.</p>
         */
        @Test
        void aTileSendsNoExtentAndAnAreaSendsOne() {
            Map<String, Object> tile = DrawCodec.encodeHighlight(
                    new DrawCommand.Tile("k", STYLE, TILE_X, TILE_Y, PLANE, DrawCaption.NONE));
            Map<String, Object> area = DrawCodec.encodeHighlight(
                    new DrawCommand.Area("k", STYLE, TILE_X, TILE_Y, 5, 7, PLANE,
                            DrawCaption.NONE));

            assertAll(
                    // Whole tiles, not sub-tiles: the handler is where the units meet.
                    () -> assertEquals(TILE_X, tile.get("x")),
                    () -> assertEquals(TILE_Y, tile.get("y")),
                    () -> assertEquals(PLANE, tile.get("plane")),
                    () -> assertFalse(tile.containsKey("w"),
                            "highlight_tile refuses an extent rather than ignoring it"),
                    () -> assertFalse(tile.containsKey("h")),
                    () -> assertEquals(5, area.get("w")),
                    () -> assertEquals(7, area.get("h")),
                    () -> assertEquals(PLANE, area.get("plane")));
        }

        /**
         * A highlight's caption is spelled {@code label}, never {@code text}.
         *
         * <p>The producer refuses {@code text} on an entity or a tile with
         * {@code entity names its caption "label", not "text"}. The spelling is chosen
         * by the encoder from the command's kind rather than by the caller, so that
         * refusal is unreachable — this is what makes that claim checkable.</p>
         */
        @Test
        void aCaption_isSpelledLabelAndAFixedPointIsSpelledValue() {
            Map<String, Object> labelled = DrawCodec.encodeHighlight(
                    new DrawCommand.Tile("k", STYLE, TILE_X, TILE_Y, PLANE,
                            DrawCaption.of("here", DrawFont.LARGE)));
            Map<String, Object> number = DrawCodec.encodeHighlight(
                    entityWithCaption(DrawCaption.of(1234, 2)));
            Map<String, Object> bare = DrawCodec.encodeHighlight(entity(EntityRef.NPC));

            assertAll(
                    () -> assertEquals("here", labelled.get("label")),
                    () -> assertEquals("large", labelled.get("font")),
                    () -> assertFalse(labelled.containsKey("text")),
                    () -> assertEquals(1234L, number.get("value")),
                    () -> assertEquals(2, number.get("decimals")),
                    () -> assertFalse(number.containsKey("label")),
                    () -> assertFalse(number.containsKey("text")),
                    // No caption means no payload and no font.
                    () -> assertFalse(bare.containsKey("label")),
                    () -> assertFalse(bare.containsKey("font")));
        }

        private DrawCommand.Entity entityWithCaption(DrawCaption caption) {
            return new DrawCommand.Entity("k", STYLE, EntityRef.NPC, NPC_INDEX, 1, 1, caption);
        }

        /** Every styling parameter a highlight honours is actually sent. */
        @Test
        void everyHighlight_sendsTheStylingTheProducerResolves() {
            for (DrawCommand.Highlight highlight : everyHighlight()) {
                Map<String, Object> encoded = DrawCodec.encodeHighlight(highlight);
                assertAll(
                        () -> assertEquals(highlight.key(), encoded.get("key")),
                        () -> assertEquals(highlight.style().color(), encoded.get("color")),
                        () -> assertEquals(highlight.style().thickness(),
                                encoded.get("thickness")),
                        () -> assertEquals(highlight.style().isFilled(), encoded.get("filled")),
                        () -> assertEquals(highlight.style().z(), encoded.get("z")),
                        // TTL always goes on the wire, so debug_draw_list reports the
                        // lifetime a highlight actually got rather than a hidden default.
                        () -> assertEquals(highlight.style().ttlMs(), encoded.get("ttl_ms")));
            }
        }

        /** Same lockstep guard as the primitives: no key the handler would skip. */
        @Test
        void everyHighlight_encodesOnlyKeysTheProducerParses() {
            for (DrawCommand.Highlight highlight : everyHighlight()) {
                for (String key : DrawCodec.encodeHighlight(highlight).keySet()) {
                    assertTrue(PRODUCER_KEYS.contains(key),
                            () -> highlight.kind() + " encodes \"" + key
                                    + "\", which MatchDrawParam does not read");
                }
            }
        }

        /** Same three malformed-item properties: map, string keys, non-null values. */
        @Test
        void everyHighlight_encodesStringKeysAndNoNullValues() {
            for (DrawCommand.Highlight highlight : everyHighlight()) {
                Map<String, Object> encoded = DrawCodec.encodeHighlight(highlight);
                assertFalse(encoded.isEmpty(), () -> highlight + " encodes nothing");
                encoded.forEach((key, value) -> assertAll(
                        () -> assertNotNull(key),
                        () -> assertFalse(key.isEmpty()),
                        () -> assertNotNull(value, () -> "null value for " + key),
                        () -> assertTrue(isExpectedType(key, value),
                                () -> highlight.kind() + " encodes " + key + " as "
                                        + value.getClass().getSimpleName())));
            }
        }
    }
}
