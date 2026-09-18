package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.draw.DrawCaption;
import com.botwithus.bot.api.draw.DrawCommand;
import com.botwithus.bot.api.draw.DrawFont;
import com.botwithus.bot.api.draw.DrawKind;
import com.botwithus.bot.api.draw.DrawSpace;
import com.botwithus.bot.api.draw.DrawStyle;
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
            "label", "value", "decimals", "font");

    private static final DrawStyle STYLE = DrawStyle.DEFAULT;

    /** One of every sealed variant, including a world-space one. */
    private static List<DrawCommand> everyVariant() {
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
        for (DrawCommand command : everyVariant()) {
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
        for (DrawCommand command : everyVariant()) {
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
        for (DrawCommand command : everyVariant()) {
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
        for (DrawCommand command : everyVariant()) {
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
        for (DrawCommand command : everyVariant()) {
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
        for (DrawCommand command : everyVariant()) {
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
            case "filled", "closed" -> value instanceof Boolean;
            case "points" -> value instanceof List<?>;
            default -> value instanceof Number;
        };
    }
}
