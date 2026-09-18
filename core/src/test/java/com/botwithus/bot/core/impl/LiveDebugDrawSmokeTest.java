package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.draw.Colors;
import com.botwithus.bot.api.draw.Draw;
import com.botwithus.bot.api.draw.DrawBatchResult;
import com.botwithus.bot.api.draw.DrawCommand;
import com.botwithus.bot.api.draw.DrawEntry;
import com.botwithus.bot.api.draw.DrawFrame;
import com.botwithus.bot.api.draw.DrawLimits;
import com.botwithus.bot.api.draw.DrawStats;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.rpc.RpcException;
import com.botwithus.bot.core.rpc.RpcMetrics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The debug-drawing API end to end against a running, injected client.
 *
 * <p>Stands up the real {@code PipeClient -> RpcClient -> GameAPIImpl -> Draw}
 * stack — the same path a BotScript takes — so every claim here is about wire
 * traffic rather than about a mock.</p>
 *
 * <p>The assertion that matters is the <b>round-trip count</b>, and here it is
 * counted from {@link RpcMetrics}, which is incremented inside
 * {@code RpcClient.doCall} once per actual pipe exchange. A batching helper that
 * quietly issued one call per primitive would still draw everything correctly and
 * would pass every functional assertion in this file; only the count catches it.</p>
 *
 * <p>Disabled by default — opt in with {@code -Dbotwithus.smoke.live=true}, which
 * the {@code :core:harnessTest} and {@code :core:liveSmokeTest} tasks set. Skips
 * (assumption) when no pipe is visible or the producer predates the debug-draw
 * method group. Read-only with respect to the game: it draws and clears its own
 * overlay commands and queues no actions.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "botwithus.smoke.live", matches = "true")
class LiveDebugDrawSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(LiveDebugDrawSmokeTest.class);

    private static final String SET = "debug_draw_set";
    private static final String BATCH = "debug_draw_set_batch";

    /** Key prefix for everything this class draws, so a stray leftover is identifiable. */
    private static final String PREFIX = "live-draw-";

    private static final int FRAME_PRIMITIVES = 20;
    private static final int PROBE_BOX_SIZE = 40;

    /**
     * Probe geometry. Well inside the client area rather than hard against a
     * corner, and the probe is inset from the box so an off-by-one in the
     * rasteriser's edge handling cannot decide the result either way.
     */
    private static final int PROBE_ORIGIN = 100;
    private static final int PROBE_BOX_W = 200;
    private static final int PROBE_BOX_H = 150;
    private static final int PROBE_INSET = 20;

    /** {@code source} values for {@code debug_draw_probe_pixels}. */
    private static final int SCREEN = 0;
    private static final int OVERLAY_SURFACE = 1;

    private static final int PRESENT_POLL_ATTEMPTS = 40;
    private static final long PRESENT_POLL_SLEEP_MS = 50L;
    private static final long SHORT_TTL_MS = 5000L;
    private static final int BACKPACK_INTERFACE = 1473;
    private static final int BACKPACK_SLOT_COMPONENT = 5;

    private RpcClient rpc;
    private Draw draw;
    private boolean wasEnabled;

    @BeforeAll
    void connect() {
        List<String> pipes = PipeClient.scanPipes(PipeClient.NAME_PREFIX);
        Assumptions.assumeFalse(pipes.isEmpty(),
                "no BotWithUs_<pid> pipe visible — inject the DLL into a running client");

        PipeClient pipe = new PipeClient(pipes.getFirst());
        rpc = new RpcClient(pipe);
        GameAPI api = new GameAPIImpl(rpc);
        draw = api.draw();

        DrawStats stats = probeForDrawSupport();
        Assumptions.assumeTrue(stats != null,
                "producer has no debug_draw_* method group — rebuild and reinject the agent");

        wasEnabled = draw.isEnabled();
        if (!wasEnabled) {
            draw.setEnabled(true);
        }
        log.info("connected to {}; overlay backend={} enabled={} surface={}x{}",
                pipe.getPipePath(), stats.backend(), stats.isEnabled(),
                stats.surfaceWidth(), stats.surfaceHeight());
    }

    private DrawStats probeForDrawSupport() {
        try {
            return draw.stats();
        } catch (RpcException e) {
            log.info("debug_draw_stats unavailable: {}", e.getMessage());
            return null;
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

    /** Actual pipe round-trips for one method, as the transport counted them. */
    private long roundTrips(String method) {
        RpcMetrics.MethodStats stats = rpc.getMetrics().snapshot().get(method);
        return stats == null ? 0L : stats.callCount();
    }

    // -------------------------------------------------------- the round-trip count

    /**
     * The headline claim: a frame of twenty primitives is <b>one</b> exchange on
     * the pipe, not twenty.
     */
    @Test
    void frame_withTwentyPrimitives_isExactlyOneRoundTrip() {
        long batchesBefore = roundTrips(BATCH);
        long setsBefore = roundTrips(SET);

        DrawBatchResult result;
        try (DrawFrame frame = draw.frame()) {
            for (int i = 0; i < FRAME_PRIMITIVES; i++) {
                frame.rect(PREFIX + "box-" + i, i * 2, i * 2, PROBE_BOX_SIZE, PROBE_BOX_SIZE)
                        .color(Colors.CYAN)
                        .ttl(SHORT_TTL_MS)
                        .submit();
            }
            result = frame.flush();
        }

        assertAll(
                () -> assertEquals(1L, roundTrips(BATCH) - batchesBefore,
                        "a frame must collapse to one debug_draw_set_batch"),
                () -> assertEquals(0L, roundTrips(SET) - setsBefore,
                        "a frame must not fall back to per-primitive debug_draw_set"),
                () -> assertEquals(FRAME_PRIMITIVES, result.applied()),
                () -> assertTrue(result.isComplete(), () -> "producer refused "
                        + result.dropped() + ": " + result.firstError()));
    }

    /**
     * The positive control for the {@code 0} above: {@code debug_draw_set} is
     * wired and reachable over this very pipe, so "no set calls" is a fact about
     * batching rather than about a dead method.
     */
    @Test
    void submit_outsideAFrame_isOneRoundTripPerPrimitive() {
        long before = roundTrips(SET);

        draw.rect(PREFIX + "single-a", 10, 10, PROBE_BOX_SIZE, PROBE_BOX_SIZE)
                .ttl(SHORT_TTL_MS).submit();
        draw.rect(PREFIX + "single-b", 60, 10, PROBE_BOX_SIZE, PROBE_BOX_SIZE)
                .ttl(SHORT_TTL_MS).submit();

        assertEquals(2L, roundTrips(SET) - before);
    }

    /**
     * The producer rejects an over-size batch outright rather than truncating it,
     * so a frame that exceeds the cap has to split or lose everything. This
     * asserts both halves: the raw call fails, and the frame does not.
     */
    @Test
    void framePastTheBatchCap_splitsWhileTheRawCallWouldHaveFailed() {
        int overflowing = DrawLimits.MAX_BATCH_ITEMS + 1;
        List<Map<String, Object>> rawItems = new ArrayList<>(overflowing);
        for (int i = 0; i < overflowing; i++) {
            rawItems.add(Map.of("key", PREFIX + "raw-" + i, "kind", "rect",
                    "x", 0, "y", 0, "w", 2, "h", 2, "ttl_ms", SHORT_TTL_MS));
        }

        RpcException raw = assertThrows(RpcException.class,
                () -> rpc.callSync(BATCH, Map.of("items", rawItems)),
                "an over-size batch is a hard error, not a partial success");

        long before = roundTrips(BATCH);
        DrawBatchResult result;
        DrawFrame frame = draw.frame();
        for (int i = 0; i < overflowing; i++) {
            frame.rect(PREFIX + "split-" + i, i % 100, i % 100, 2, 2).ttl(SHORT_TTL_MS).submit();
        }
        result = frame.flush();

        assertAll(
                () -> assertTrue(raw.getMessage().contains("256"),
                        () -> "unexpected producer message: " + raw.getMessage()),
                () -> assertEquals(2L, roundTrips(BATCH) - before,
                        "257 commands must split into two batches"),
                () -> assertEquals(overflowing, result.submitted()));
    }

    // ------------------------------------------------------------ wire semantics

    /**
     * A listed TTL is what is <i>left</i>, not what was sent. Round-tripping a TTL
     * through {@code debug_draw_list} is not symmetric and this pins that.
     */
    @Test
    void list_reportsRemainingTtlRatherThanTheOneSent() {
        String key = PREFIX + "ttl";
        draw.rect(key, 5, 5, PROBE_BOX_SIZE, PROBE_BOX_SIZE).ttl(SHORT_TTL_MS).submit();

        DrawEntry entry = draw.list().stream()
                .filter(e -> key.equals(e.key()))
                .findFirst()
                .orElse(null);

        assertAll(
                () -> assertNotNull(entry, "the command we just set is not in our own list"),
                () -> assertTrue(entry.remainingTtlMs() <= SHORT_TTL_MS,
                        () -> "remaining " + entry.remainingTtlMs() + " exceeds the sent TTL"),
                () -> assertTrue(entry.remainingTtlMs() > 0));
    }

    /**
     * The auto key this host computes is the key the producer would have
     * generated. The format is the only way a caller who omitted a key can clear
     * the highlight, so it is asserted against the producer rather than assumed.
     */
    @Test
    void componentAutoKey_matchesTheKeyTheProducerGenerates() {
        Map<String, Object> reply = rpc.callSync("highlight_component",
                Map.of("iface", BACKPACK_INTERFACE, "comp", BACKPACK_SLOT_COMPONENT,
                        "ttl_ms", SHORT_TTL_MS));

        assertEquals(
                DrawCommand.ComponentTarget.autoKey(BACKPACK_INTERFACE, BACKPACK_SLOT_COMPONENT),
                reply.get("key"));
    }

    /**
     * World space is on the wire and rejected until projection lands. The
     * signature ships now; this pins that the rejection is the specific documented
     * one rather than a generic parse failure, so the day it starts working is
     * detectable.
     */
    @Test
    void worldSpace_isRejectedWithItsOwnError() {
        RpcException thrown = assertThrows(RpcException.class,
                () -> draw.tile(PREFIX + "world", 3200, 3200).ttl(SHORT_TTL_MS).submit());

        assertTrue(thrown.getMessage().contains("world space"),
                () -> "unexpected producer message: " + thrown.getMessage());
    }

    @Test
    void clearAll_emptiesOurOwnListAndNothingElse() {
        draw.rect(PREFIX + "a", 0, 0, 10, 10).ttl(SHORT_TTL_MS).submit();
        draw.rect(PREFIX + "b", 0, 0, 10, 10).ttl(SHORT_TTL_MS).submit();

        assertAll(
                () -> assertTrue(draw.clearAll() >= 2),
                () -> assertTrue(draw.list().isEmpty()));
    }

    @Test
    void clear_removesOnlyTheNamedKey() {
        draw.rect(PREFIX + "keep", 0, 0, 10, 10).ttl(SHORT_TTL_MS).submit();
        draw.rect(PREFIX + "drop", 0, 0, 10, 10).ttl(SHORT_TTL_MS).submit();

        assertEquals(1, draw.clear(PREFIX + "drop"));

        List<String> left = draw.list().stream().map(DrawEntry::key).toList();
        assertAll(
                () -> assertTrue(left.contains(PREFIX + "keep")),
                () -> assertFalse(left.contains(PREFIX + "drop")));
    }

    // --------------------------------------------------- did it reach the screen

    /**
     * The one assertion that is about pixels rather than counters: a filled box
     * submitted through this API is actually rasterised.
     *
     * <p>{@code debug_draw_probe_pixels} reads real pixels back and is falsifiable
     * — break the renderer and the match count goes to zero. It samples two
     * different things, and this test keeps them apart deliberately:</p>
     *
     * <ul>
     *   <li><b>{@code source: 1}, the overlay's own surface</b> — "did the
     *       renderer draw it". Hard-asserted: nothing outside this process can
     *       make it fail.</li>
     *   <li><b>{@code source: 0}, the screen</b> — "did it reach the screen".
     *       Reported, and asserted only when the producer says the client rect was
     *       not occluded. Another window on top is an environment fact, not a
     *       regression in this API, and failing on it would make this test a
     *       function of which window happens to have focus.</li>
     * </ul>
     *
     * <p>Note the wait: {@code hasPresented} is true from the first frame the
     * overlay ever drew, so it says nothing about <i>this</i> command. The poll
     * below waits for a present that happened after the submit.</p>
     */
    @Test
    void filledRect_isRasterisedAndReachesTheScreenWhenVisible() {
        DrawStats before = draw.stats();

        draw.rect(PREFIX + "probe", PROBE_ORIGIN, PROBE_ORIGIN, PROBE_BOX_W, PROBE_BOX_H)
                .color(Colors.MAGENTA)
                .filled()
                .ttl(SHORT_TTL_MS)
                .submit();

        Assumptions.assumeTrue(awaitPresentAfter(before.backendPresents()),
                "overlay presented no frame after the submit — headless or minimised client");

        Map<String, Object> surface = probe(OVERLAY_SURFACE);
        Map<String, Object> screen = probe(SCREEN);
        log.info("probe: surface={} screen={}", surface, screen);

        assertAll(
                () -> assertEquals(before.presentFailures(), draw.stats().presentFailures(),
                        "the renderer failed a present while drawing this frame"),
                () -> assertTrue(MapHelper.getInt(surface, "matched") > 0,
                        () -> "the renderer drew no magenta inside the box we filled: " + surface),
                () -> assertTrue(MapHelper.getBool(screen, "occluded")
                                || MapHelper.getInt(screen, "matched") > 0,
                        () -> "client rect was not occluded, yet no magenta reached the "
                                + "screen: " + screen));
    }

    private Map<String, Object> probe(int source) {
        return rpc.callSync("debug_draw_probe_pixels",
                Map.of("x", PROBE_ORIGIN + PROBE_INSET, "y", PROBE_ORIGIN + PROBE_INSET,
                        "w", PROBE_BOX_W - PROBE_INSET * 2, "h", PROBE_BOX_H - PROBE_INSET * 2,
                        "color", Colors.MAGENTA, "source", source));
    }

    /**
     * Waits for a present that happened after the submit.
     *
     * <p>Deliberately does <b>not</b> look at {@code presentFailures}. That counter
     * is a process-lifetime latch — the producer only ever increments it and never
     * resets it — so gating this on {@code presentFailures == 0} would mean a
     * single failed present at any earlier point in the client's life permanently
     * turned the pixel assertions below into a skip, and reported it as "headless
     * or minimised". A broken renderer and a minimised window would have produced
     * byte-identical output. The failure count is compared before and after the
     * submit by the caller instead, where it is a hard assertion rather than a
     * gate.</p>
     */
    private boolean awaitPresentAfter(long presentsBefore) {
        for (int attempt = 0; attempt < PRESENT_POLL_ATTEMPTS; attempt++) {
            DrawStats stats = draw.stats();
            if (stats.backendPresents() > presentsBefore) {
                return true;
            }
            try {
                Thread.sleep(PRESENT_POLL_SLEEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
