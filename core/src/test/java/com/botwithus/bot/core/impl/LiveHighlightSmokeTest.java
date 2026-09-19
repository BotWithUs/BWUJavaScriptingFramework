package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.draw.Colors;
import com.botwithus.bot.api.draw.Draw;
import com.botwithus.bot.api.draw.DrawCommand;
import com.botwithus.bot.api.draw.DrawEntry;
import com.botwithus.bot.api.draw.DrawFont;
import com.botwithus.bot.api.draw.DrawKind;
import com.botwithus.bot.api.draw.DrawLimits;
import com.botwithus.bot.api.draw.DrawSpace;
import com.botwithus.bot.api.draw.EntityRef;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.rpc.RpcException;
import com.botwithus.bot.core.rpc.RpcRemoteException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code highlight_*} binding against a real agent: keys, kinds and refusals.
 *
 * <p>Deliberately split from {@code LiveHighlightTrackingTest}, which needs the client
 * in the world and an NPC that walks. Everything here works from the login screen,
 * because {@code overlay::Set} <b>stores</b> a command without resolving it — a
 * highlight is accepted, keyed and listed with {@code resolved: false} until there is a
 * scene to project into. That split is why the contract half of this feature can be
 * verified on any injected client and only the tracking claim needs a live world.</p>
 *
 * <p>The assertions that matter here are the <b>auto-key formats</b>. This host computes
 * them so {@code submit()} can answer a key before the round-trip, which means the
 * format is duplicated on both sides of the wire — and a caller who omitted a key has no
 * other way to name the highlight in order to clear it. So each one is checked by
 * omitting the key over the raw RPC and reading back what the producer chose, rather
 * than by trusting that the two implementations agree.</p>
 *
 * <p>Read-only with respect to the game: it draws and clears its own overlay commands
 * and queues no actions. Disabled by default — opt in with
 * {@code -Dbotwithus.smoke.live=true}.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "botwithus.smoke.live", matches = "true")
class LiveHighlightSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(LiveHighlightSmokeTest.class);

    private static final String HIGHLIGHT_ENTITY = "highlight_entity";
    private static final String HIGHLIGHT_TILE = "highlight_tile";
    private static final String HIGHLIGHT_AREA = "highlight_area";

    private static final String PREFIX = "live-hl-";
    private static final long TTL_MS = 5000L;

    private static final int NPC_INDEX = 42;
    private static final int PLAYER_INDEX = 7;
    private static final int TILE_X = 3200;
    private static final int TILE_Y = 3213;
    private static final int PLANE = 0;
    private static final int AREA_W = 5;
    private static final int AREA_H = 7;

    private RpcClient rpc;
    private Draw draw;
    private boolean wasEnabled;

    @BeforeAll
    void connect() {
        List<String> pipes = PipeClient.scanPipes(PipeClient.NAME_PREFIX);
        Assumptions.assumeFalse(pipes.isEmpty(),
                "SKIPPED, not passed: no BotWithUs_<pid> pipe visible — nothing was checked. "
                        + "Inject the agent into a running client.");

        rpc = new RpcClient(new PipeClient(pipes.getFirst()));
        GameAPI api = new GameAPIImpl(rpc);
        draw = api.draw();

        Assumptions.assumeTrue(hasHighlightSupport(),
                "SKIPPED, not passed: this agent predates the highlight_* group, so none of "
                        + "the key formats or refusals below were checked against it.");

        wasEnabled = draw.isEnabled();
        if (!wasEnabled) {
            draw.setEnabled(true);
        }
        log.info("connected to {} for highlight contract checks", pipes.getFirst());
    }

    /**
     * Whether the highlight handlers are present <i>and wired</i>.
     *
     * <p>Probed by calling with no entity reference and matching the refusal, not by
     * reading {@code rpc.list_methods}: a catalogue entry proves a row in a table, while
     * a refusal naming the missing parameter proves the handler body ran.</p>
     */
    private boolean hasHighlightSupport() {
        try {
            rpc.callSync(HIGHLIGHT_ENTITY, Map.of("ttl_ms", 1));
            return true;
        } catch (RpcException e) {
            return e.getMessage() != null
                    && e.getMessage().contains("needs npc, player or self");
        }
    }

    @AfterEach
    void clearOurOwnDrawings() {
        if (draw != null) {
            draw.clearAll();
        }
    }

    @AfterAll
    void disconnect() {
        if (draw != null && !wasEnabled) {
            draw.setEnabled(false);
        }
        if (rpc != null) {
            rpc.close();
        }
    }

    private Map<String, DrawEntry> listByKey() {
        Map<String, DrawEntry> byKey = new LinkedHashMap<>();
        for (DrawEntry entry : draw.list()) {
            byKey.put(entry.key(), entry);
        }
        return byKey;
    }

    /** The key the producer generates when the caller names none. */
    private Object producerAutoKey(String method, Map<String, Object> params) {
        Map<String, Object> withTtl = new LinkedHashMap<>(params);
        withTtl.put("ttl_ms", TTL_MS);
        return rpc.callSync(method, withTtl).get("key");
    }

    // ------------------------------------------------------- the auto-key contract

    /**
     * An entity auto key names the entity and nothing else.
     *
     * <p>Checked against the producer because the format is duplicated: this host
     * computes it up front so {@code submit()} can answer it. The producer's own comment
     * above {@code Handle_HighlightEntity} still claims {@code npc:<index>:0}; the code
     * does not append the trailing field, and <b>this assertion is what decides which of
     * the two to believe</b> rather than either doc.</p>
     */
    @Test
    void entityAutoKey_matchesTheKeyTheProducerGenerates() {
        assertAll(
                () -> assertEquals(DrawCommand.Entity.autoKey(EntityRef.NPC, NPC_INDEX),
                        producerAutoKey(HIGHLIGHT_ENTITY, Map.of("npc", NPC_INDEX))),
                () -> assertEquals(DrawCommand.Entity.autoKey(EntityRef.PLAYER, PLAYER_INDEX),
                        producerAutoKey(HIGHLIGHT_ENTITY, Map.of("player", PLAYER_INDEX))),
                // self carries no index, so its key is the bare prefix.
                () -> assertEquals(DrawCommand.Entity.autoKey(EntityRef.SELF, 0),
                        producerAutoKey(HIGHLIGHT_ENTITY, Map.of("self", true))));
    }

    /** A tile auto key carries its plane; an area's carries its extent too. */
    @Test
    void tileAndAreaAutoKeys_matchTheKeysTheProducerGenerates() {
        assertAll(
                () -> assertEquals(DrawCommand.Tile.autoKey(TILE_X, TILE_Y, PLANE),
                        producerAutoKey(HIGHLIGHT_TILE,
                                Map.of("x", TILE_X, "y", TILE_Y, "plane", PLANE))),
                () -> assertEquals(
                        DrawCommand.Area.autoKey(TILE_X, TILE_Y, AREA_W, AREA_H, PLANE),
                        producerAutoKey(HIGHLIGHT_AREA,
                                Map.of("x", TILE_X, "y", TILE_Y, "w", AREA_W, "h", AREA_H,
                                        "plane", PLANE))));
    }

    /**
     * The keyless helpers on this API submit under exactly that format.
     *
     * <p>The two tests above pin the producer's generator; this pins that the helpers
     * reach the same string, which is what makes {@code draw.clear(draw.npc(npc).submit())}
     * a thing that works.</p>
     */
    @Test
    void theKeylessHelpers_landOnTheProducersOwnKeys() {
        assertAll(
                () -> assertEquals("npc:" + NPC_INDEX,
                        draw.entity("npc:" + NPC_INDEX, EntityRef.NPC, NPC_INDEX)
                                .ttl(TTL_MS).submit()),
                () -> assertEquals("self", draw.self().ttl(TTL_MS).submit()),
                () -> assertEquals(DrawCommand.Tile.autoKey(TILE_X, TILE_Y, PLANE),
                        draw.tile(TILE_X, TILE_Y, PLANE).ttl(TTL_MS).submit()),
                () -> assertEquals(
                        DrawCommand.Area.autoKey(TILE_X, TILE_Y, AREA_W, AREA_H, PLANE),
                        draw.area(TILE_X, TILE_Y, AREA_W, AREA_H, PLANE).ttl(TTL_MS).submit()));
    }

    // ----------------------------------------------------- what comes back out

    /**
     * A highlight reads back spelled as the producer's own kind — and both of those
     * kinds are ones {@code debug_draw_set} refuses.
     *
     * <p>Before this change {@link DrawKind#fromWireName} knew only the six a caller can
     * send, so a listed highlight — one this host drew, or one NXTDebugger drew on the
     * same client — decoded to a {@code null} kind. The area leg is the one worth
     * reading twice: it comes back as {@code tile}, because {@code highlight_area} and
     * {@code highlight_tile} write one producer-side kind.</p>
     */
    @Test
    void highlightsReadBack_asTheProducersOwnKinds() {
        String entity = draw.entity(PREFIX + "e", EntityRef.NPC, NPC_INDEX).ttl(TTL_MS).submit();
        String tile = draw.tile(PREFIX + "t", TILE_X, TILE_Y, PLANE).ttl(TTL_MS).submit();
        String area = draw.area(PREFIX + "a", TILE_X, TILE_Y, AREA_W, AREA_H, PLANE)
                .ttl(TTL_MS).submit();

        Map<String, DrawEntry> byKey = listByKey();
        log.info("listed highlights: {} / {} / {}",
                byKey.get(entity), byKey.get(tile), byKey.get(area));

        assertAll(
                () -> assertNotNull(byKey.get(entity), () -> "no entry for " + entity),
                () -> assertSame(DrawKind.ENTITY, byKey.get(entity).kind(),
                        "the producer spells an entity highlight \"entity\""),
                () -> assertSame(DrawKind.TILE, byKey.get(tile).kind()),
                // An area is a tile with a bigger extent, not a kind of its own.
                () -> assertSame(DrawKind.TILE, byKey.get(area).kind(),
                        "highlight_area and highlight_tile write one producer-side kind"),
                // Every highlight is world space whatever the caller did or did not say.
                () -> assertSame(DrawSpace.WORLD,
                        byKey.get(entity).space()));
    }

    /**
     * Neither semantic kind is spellable on {@code debug_draw_set}, which is why they
     * need their own calls at all.
     *
     * <p>This is the producer-side half of {@link DrawKind#isSpellable()}. The typed API
     * cannot make this call — a highlight is not a
     * {@link DrawCommand.Primitive} — so the check is that the raw call fails while the
     * binding has no way to make it.</p>
     */
    @Test
    void theSemanticKinds_areRefusedOnAPlainSet() {
        RpcException entity = assertThrows(RpcException.class,
                () -> rpc.callSync("debug_draw_set", Map.of("key", PREFIX + "bad-e",
                        "kind", "entity", "npc", NPC_INDEX, "ttl_ms", TTL_MS)));
        RpcException tile = assertThrows(RpcException.class,
                () -> rpc.callSync("debug_draw_set", Map.of("key", PREFIX + "bad-t",
                        "kind", "tile", "x", TILE_X, "y", TILE_Y, "ttl_ms", TTL_MS)));

        assertAll(
                () -> assertTrue(entity.getMessage().contains("unknown kind"),
                        () -> "unexpected: " + entity.getMessage()),
                () -> assertTrue(tile.getMessage().contains("unknown kind"),
                        () -> "unexpected: " + tile.getMessage()));
    }

    /** A highlight's caption comes back in {@code text}, the same field a text command uses. */
    @Test
    void aHighlightCaption_readsBackInTheTextField() {
        String labelled = draw.tile(PREFIX + "labelled", TILE_X, TILE_Y, PLANE)
                .label("Bank")
                .font(DrawFont.HEADING)
                .ttl(TTL_MS)
                .submit();
        String numbered = draw.entity(PREFIX + "numbered", EntityRef.NPC, NPC_INDEX)
                .value(1234, 2)
                .ttl(TTL_MS)
                .submit();

        Map<String, DrawEntry> byKey = listByKey();

        assertAll(
                () -> assertEquals("Bank", byKey.get(labelled).text()),
                () -> assertSame(DrawFont.HEADING, byKey.get(labelled).font()),
                // The producer formats the fixed point, because the wire has no float.
                () -> assertEquals("12.34", byKey.get(numbered).text()));
    }

    // --------------------------------------------------------------- refusals

    /**
     * {@code highlight_tile} <b>refuses</b> an extent rather than dropping it.
     *
     * <p>Unreachable from this API — a tile and an area are different records with
     * different fields — so this asserts the producer still refuses it, which is the
     * reason the split exists. A silently-dropped {@code w} is how a caller ends up
     * convinced a 5x5 highlight rendered as 1x1 because of a projection bug.</p>
     */
    @Test
    void highlightTile_refusesAnExtentRatherThanIgnoringIt() {
        RpcException thrown = assertThrows(RpcException.class,
                () -> rpc.callSync(HIGHLIGHT_TILE, Map.of("key", PREFIX + "wide",
                        "x", TILE_X, "y", TILE_Y, "w", AREA_W, "h", AREA_H, "ttl_ms", TTL_MS)));

        assertAll(
                () -> assertTrue(thrown.getMessage().contains("highlight_area"),
                        () -> "the refusal must name the call that takes an extent: "
                                + thrown.getMessage()),
                // A refused call is a remote error, not a transport failure. That
                // distinction is what lets DrawFrame count a refusal and still let a
                // dead pipe through — see GameAPIImpl.sendOneHighlight.
                () -> assertInstanceOfRemoteError(thrown));
    }

    /**
     * An entity needs exactly one reference: none is an error and two is an error.
     *
     * <p>Unreachable from this API, because {@link EntityRef} is one parameter rather
     * than two nullable ones. Asserted against the producer so the modelling decision
     * stays justified by the wire rather than by memory.</p>
     */
    @Test
    void highlightEntity_refusesNoReferenceAndTwoReferences() {
        RpcException none = assertThrows(RpcException.class,
                () -> rpc.callSync(HIGHLIGHT_ENTITY,
                        Map.of("key", PREFIX + "none", "ttl_ms", TTL_MS)));
        RpcException both = assertThrows(RpcException.class,
                () -> rpc.callSync(HIGHLIGHT_ENTITY, Map.of("key", PREFIX + "both",
                        "npc", NPC_INDEX, "player", PLAYER_INDEX, "ttl_ms", TTL_MS)));

        assertAll(
                () -> assertTrue(none.getMessage().contains("needs npc, player or self"),
                        () -> "unexpected: " + none.getMessage()),
                () -> assertTrue(both.getMessage().contains("not both"),
                        () -> "unexpected: " + both.getMessage()));
    }

    /**
     * A refused highlight surfaces as {@link RpcRemoteException}, not as a bare
     * {@link RpcException}.
     *
     * <p>Load-bearing rather than cosmetic: {@code GameAPIImpl.drawHighlights} catches
     * exactly this subclass to report a refusal as a {@code dropped} count while letting
     * a dead pipe propagate. If the transport stopped raising the subclass for a remote
     * error, a frame would start throwing on a refused highlight and
     * {@code DrawFrame.close()} would be able to take a script's tick down.</p>
     */
    private static void assertInstanceOfRemoteError(RpcException thrown) {
        assertSame(RpcRemoteException.class, thrown.getClass(),
                () -> "a producer refusal must arrive as RpcRemoteException so a frame can "
                        + "count it rather than throw; got " + thrown.getClass().getName());
    }

    /**
     * {@code z} is refused rather than narrowed, on a highlight as on anything else.
     *
     * <p>Worth one live assertion because the host-side check in {@code DrawStyle} was
     * written when the producer <i>did</i> narrow it — {@code z: 100000} became
     * {@code -31072} and silently repainted. The producer now refuses, so the host check
     * agrees with a real error instead of substituting for a missing one, and this is
     * what keeps that claim true rather than remembered.</p>
     */
    @Test
    void anOutOfRangeZ_isRefusedByTheProducerRatherThanNarrowed() {
        int narrowsToADifferentOrder = 100_000;
        RpcException thrown = assertThrows(RpcException.class,
                () -> rpc.callSync(HIGHLIGHT_TILE, Map.of("key", PREFIX + "z",
                        "x", TILE_X, "y", TILE_Y, "z", narrowsToADifferentOrder,
                        "ttl_ms", TTL_MS)));

        assertTrue(thrown.getMessage().contains("z must be"),
                () -> "the producer no longer refuses an out-of-range z, so DrawLimits.MIN_Z's "
                        + "javadoc and DrawStyle's message are wrong again: "
                        + thrown.getMessage());
    }

    /**
     * A highlight sent through this API costs exactly one round-trip, and a frame does
     * not turn that into a batch.
     *
     * <p>Counted from {@link com.botwithus.bot.core.rpc.RpcMetrics}, which the transport
     * increments once per actual pipe exchange. The claim being protected is not
     * performance — it is that a highlight never enters a batch, because the producer
     * would refuse a batched one as {@code unknown kind} and the frame's
     * {@code dropped} count is the only place it would show.</p>
     */
    @Test
    void aFrameWithHighlights_batchesThePrimitivesAndSendsTheHighlightsSeparately() {
        long batchesBefore = roundTrips("debug_draw_set_batch");
        long setsBefore = roundTrips("debug_draw_set");
        long entitiesBefore = roundTrips(HIGHLIGHT_ENTITY);

        try (var frame = draw.frame()) {
            frame.rect(PREFIX + "box-a", 0, 0, 10, 10).ttl(TTL_MS).submit();
            frame.rect(PREFIX + "box-b", 0, 0, 10, 10).ttl(TTL_MS).submit();
            frame.entity(PREFIX + "f-e", EntityRef.NPC, NPC_INDEX).ttl(TTL_MS).submit();
            frame.self(PREFIX + "f-self").ttl(TTL_MS).submit();
        }

        assertAll(
                () -> assertEquals(1L, roundTrips("debug_draw_set_batch") - batchesBefore,
                        "the two rects must collapse into one batch"),
                () -> assertEquals(0L, roundTrips("debug_draw_set") - setsBefore,
                        "a frame must not fall back to per-primitive debug_draw_set"),
                () -> assertEquals(2L, roundTrips(HIGHLIGHT_ENTITY) - entitiesBefore,
                        "each highlight is its own call — no batch on this wire carries one"));
    }

    /** Actual pipe round-trips for one method, as the transport counted them. */
    private long roundTrips(String method) {
        var stats = rpc.getMetrics().snapshot().get(method);
        return stats == null ? 0L : stats.callCount();
    }

    /**
     * A tile helper speaks tiles, and the producer's own key proves the unit survived.
     *
     * <p>Sending sub-tiles to {@code highlight_tile} would be silently accepted — 819200
     * is a legal tile as far as the parser is concerned — and would mark somewhere 256
     * times further out with no error anywhere. The producer builds its auto key from the
     * {@code x} it received, so reading that key back is a direct measurement of the unit
     * that arrived, which nothing else on this wire reports.</p>
     */
    @Test
    void theTileHelper_sendsTilesAndTheProducersKeyProvesIt() {
        Object key = producerAutoKey(HIGHLIGHT_TILE,
                Map.of("x", TILE_X, "y", TILE_Y, "plane", PLANE));
        int asSubtiles = TILE_X * DrawLimits.SUBTILE_SCALE;

        assertAll(
                () -> assertEquals("tile:" + TILE_X + ":" + TILE_Y + ":" + PLANE, key),
                () -> assertTrue(!String.valueOf(key).contains(String.valueOf(asSubtiles)),
                        () -> "the producer echoed a sub-tile coordinate, so the helper sent "
                                + "the wrong unit: " + key));
    }

    /** Colour and TTL survive the trip, so a highlight is styled like anything else. */
    @Test
    void aHighlight_honoursTheSameStylingAsAPrimitive() {
        String key = draw.tile(PREFIX + "styled", TILE_X, TILE_Y, PLANE)
                .color(Colors.MAGENTA)
                .filled()
                .ttl(TTL_MS)
                .submit();

        DrawEntry entry = listByKey().get(key);
        assertAll(
                () -> assertNotNull(entry, () -> "no entry for " + key),
                () -> assertEquals(Colors.MAGENTA, entry.color()),
                () -> assertTrue(entry.isFilled()),
                // Remaining, not what was sent — the asymmetry DrawEntry names.
                () -> assertTrue(entry.remainingTtlMs() > 0
                        && entry.remainingTtlMs() <= TTL_MS,
                        () -> "remaining TTL " + entry.remainingTtlMs()));
    }
}
