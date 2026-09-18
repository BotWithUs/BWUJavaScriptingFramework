package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.draw.DrawCommand;
import com.botwithus.bot.api.draw.DrawSpace;
import com.botwithus.bot.api.draw.DrawStyle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
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
            "text", "points");

    private static final DrawStyle STYLE = DrawStyle.DEFAULT;

    /** One of every sealed variant, including a world-space one. */
    private static List<DrawCommand> everyVariant() {
        return List.of(
                new DrawCommand.Line("k-line", DrawSpace.SCREEN, STYLE, 1, 2, 3, 4),
                new DrawCommand.Rect("k-rect", DrawSpace.SCREEN, STYLE, 1, 2, 3, 4),
                new DrawCommand.Ellipse("k-ellipse", DrawSpace.SCREEN, STYLE, 1, 2, 3, 4),
                new DrawCommand.Text("k-text", DrawSpace.SCREEN, STYLE, 1, 2, "hello"),
                new DrawCommand.Poly("k-poly", DrawSpace.SCREEN, STYLE, List.of(1, 2, 3, 4)),
                new DrawCommand.ComponentTarget("k-comp", DrawSpace.SCREEN, STYLE, 1473, 5),
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
            case "key", "kind", "space", "text" -> value instanceof String;
            case "filled", "closed" -> value instanceof Boolean;
            case "points" -> value instanceof List<?>;
            default -> value instanceof Number;
        };
    }
}
