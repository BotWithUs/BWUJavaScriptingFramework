package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.draw.Colors;
import com.botwithus.bot.api.draw.Draw;
import com.botwithus.bot.api.draw.DrawBatchResult;
import com.botwithus.bot.api.draw.DrawCommand;
import com.botwithus.bot.api.draw.DrawEntry;
import com.botwithus.bot.api.draw.DrawFrame;
import com.botwithus.bot.api.draw.DrawKind;
import com.botwithus.bot.api.draw.DrawLimits;
import com.botwithus.bot.api.draw.DrawSpace;
import com.botwithus.bot.api.draw.DrawStats;
import com.botwithus.bot.core.rpc.RpcClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The debug-drawing binding, against a mocked {@link RpcClient}.
 *
 * <p>The assertion this class exists for is the round-trip count. A
 * {@link DrawFrame} whose whole purpose is to collapse a tick's drawing into one
 * batch fails silently if it quietly issues one call per primitive: everything
 * still draws, the tests still pass, and the pipe carries twenty times the
 * traffic it should. So the count is asserted on the real
 * {@code Draw -> DrawFrame -> GameAPIImpl -> rpc.callSync} path — one mocked
 * seam, at the transport — rather than on a stand-in for it, and every
 * "no calls were made" assertion is paired with a positive control proving the
 * call it is denying is reachable at all.</p>
 */
class GameAPIImplDrawTest {

    private static final String SET = "debug_draw_set";
    private static final String BATCH = "debug_draw_set_batch";
    private static final String CLEAR = "debug_draw_clear";
    private static final String CLEAR_ALL = "debug_draw_clear_all";
    private static final String LIST = "debug_draw_list";
    private static final String ENABLE = "debug_draw_enable";
    private static final String STATS = "debug_draw_stats";

    private static final int FRAME_PRIMITIVES = 20;
    private static final int BACKPACK_INTERFACE = 1473;
    private static final int BACKPACK_SLOT_COMPONENT = 5;

    private RpcClient rpc;
    private Draw draw;

    @BeforeEach
    void setUp() {
        rpc = mock(RpcClient.class);
        draw = new GameAPIImpl(rpc).draw();
    }

    /** A batch reply that accepted everything it was handed. */
    private void batchAcceptsEverything() {
        when(rpc.callSync(eq(BATCH), anyMap())).thenAnswer(call -> {
            Map<String, Object> params = call.getArgument(1);
            return Map.of("count", itemCount(params), "dropped", 0);
        });
    }

    /**
     * How many items a captured {@code debug_draw_set_batch} parameter map carries.
     *
     * <p>rule-exception: {@code {rule:no-casts}} — the wire params are
     * {@code Map<String, Object>} by the transport's own signature, so reading a
     * list back out of one needs a reference cast. Confined to this one helper
     * rather than repeated in every test that counts a batch.</p>
     */
    @SuppressWarnings("unchecked")
    private static int itemCount(Map<String, Object> params) {
        return ((List<Object>) params.get("items")).size();
    }

    private static void fill(DrawFrame frame, int count) {
        for (int i = 0; i < count; i++) {
            frame.rect("box-" + i, i, i, 1 + i, 1 + i).submit();
        }
    }

    @Nested
    @DisplayName("frame() collapses a tick's drawing into one round-trip")
    class Batching {

        @Test
        void frame_twentyPrimitives_issuesExactlyOneBatchCall() {
            batchAcceptsEverything();

            try (DrawFrame frame = draw.frame()) {
                fill(frame, FRAME_PRIMITIVES);
            }

            assertAll(
                    () -> verify(rpc, times(1)).callSync(eq(BATCH), anyMap()),
                    () -> verify(rpc, times(0)).callSync(eq(SET), anyMap()),
                    () -> verifyNoMoreInteractions(rpc));
        }

        /**
         * The positive control for the {@code times(0)} above: {@code debug_draw_set}
         * is reachable from this very fixture, so "zero set calls" is a fact about
         * batching and not about the method being unwired.
         */
        @Test
        void submit_outsideAFrame_issuesOneSetCallPerPrimitive() {
            when(rpc.callSync(eq(SET), anyMap())).thenReturn(Map.of("key", "box-0"));

            draw.rect("box-0", 1, 2, 3, 4).submit();
            draw.rect("box-1", 1, 2, 3, 4).submit();

            assertAll(
                    () -> verify(rpc, times(2)).callSync(eq(SET), anyMap()),
                    () -> verify(rpc, times(0)).callSync(eq(BATCH), anyMap()));
        }

        @Test
        void frame_nothingSubmitted_issuesNoCallAtAll() {
            try (DrawFrame frame = draw.frame()) {
                assertEquals(0, frame.pendingCount());
            }

            verifyNoMoreInteractions(rpc);
        }

        @Test
        void frame_carriesEveryPrimitiveItWasGiven() {
            batchAcceptsEverything();
            ArgumentCaptor<Map<String, Object>> params = captor();

            try (DrawFrame frame = draw.frame()) {
                fill(frame, FRAME_PRIMITIVES);
            }

            verify(rpc).callSync(eq(BATCH), params.capture());
            assertEquals(FRAME_PRIMITIVES, itemCount(params.getValue()));
        }

        /**
         * The producer refuses an over-size batch outright rather than truncating
         * it, which would lose the part that would have fit. Splitting is the only
         * behaviour that does not silently drop drawings.
         */
        @Test
        void frame_pastTheBatchCap_splitsRatherThanTruncatingOrFailing() {
            batchAcceptsEverything();
            int overflowing = DrawLimits.MAX_BATCH_ITEMS + 1;
            DrawBatchResult result;

            DrawFrame frame = draw.frame();
            fill(frame, overflowing);
            result = frame.flush();

            assertAll(
                    () -> verify(rpc, times(2)).callSync(eq(BATCH), anyMap()),
                    () -> assertEquals(overflowing, result.applied()),
                    () -> assertTrue(result.isComplete()));
        }

        @Test
        void frame_exactlyAtTheBatchCap_stillIssuesOneCall() {
            batchAcceptsEverything();

            try (DrawFrame frame = draw.frame()) {
                fill(frame, DrawLimits.MAX_BATCH_ITEMS);
            }

            verify(rpc, times(1)).callSync(eq(BATCH), anyMap());
        }

        @Test
        void flush_thenClose_doesNotSendTheSameCommandsTwice() {
            batchAcceptsEverything();

            try (DrawFrame frame = draw.frame()) {
                fill(frame, 2);
                frame.flush();
            }

            verify(rpc, times(1)).callSync(eq(BATCH), anyMap());
        }

        @Test
        void abandon_dropsPendingWithoutSending() {
            DrawFrame frame = draw.frame();
            fill(frame, 3);

            assertAll(
                    () -> assertEquals(3, frame.abandon()),
                    () -> assertEquals(0, frame.pendingCount()));
            frame.close();
            verifyNoMoreInteractions(rpc);
        }

        @Test
        void drawSetBatch_overTheCap_refusesRatherThanLosingTheWholeBatch() {
            List<DrawCommand> tooMany = new ArrayList<>();
            for (int i = 0; i <= DrawLimits.MAX_BATCH_ITEMS; i++) {
                tooMany.add(draw.rect("box-" + i, 0, 0, 1, 1).build());
            }
            GameAPIImpl api = new GameAPIImpl(rpc);

            assertThrows(IllegalArgumentException.class, () -> api.drawSetBatch(tooMany));
            verify(rpc, times(0)).callSync(eq(BATCH), anyMap());
        }
    }

    @Nested
    @DisplayName("a batch reports refusals inside a successful reply")
    class DroppedReporting {

        @Test
        void flush_whenTheProducerRefusedSome_reportsThemRatherThanThrowing() {
            when(rpc.callSync(eq(BATCH), anyMap())).thenReturn(
                    Map.of("count", 3, "dropped", 2, "error", "draw store is full"));

            DrawFrame frame = draw.frame();
            fill(frame, 5);
            DrawBatchResult result = frame.flush();

            assertAll(
                    () -> assertEquals(3, result.applied()),
                    () -> assertEquals(2, result.dropped()),
                    () -> assertEquals(5, result.submitted()),
                    () -> assertFalse(result.isComplete()),
                    () -> assertEquals("draw store is full", result.firstError()));
        }

        @Test
        void flush_whenTheProducerRefusedEverything_isStillNotAnError() {
            when(rpc.callSync(eq(BATCH), anyMap())).thenReturn(
                    Map.of("count", 0, "dropped", 4, "error", "draw store is full"));

            DrawFrame frame = draw.frame();
            fill(frame, 4);
            DrawBatchResult result = frame.flush();

            assertAll(
                    () -> assertEquals(0, result.applied()),
                    () -> assertFalse(result.isComplete()));
        }

        /** Only the first refusal has a message on the wire; a split frame keeps it. */
        @Test
        void flush_acrossSplitBatches_keepsTheEarliestErrorMessage() {
            when(rpc.callSync(eq(BATCH), anyMap()))
                    .thenReturn(Map.of("count", 255, "dropped", 1, "error", "first"))
                    .thenReturn(Map.of("count", 0, "dropped", 1, "error", "second"));

            DrawFrame frame = draw.frame();
            fill(frame, DrawLimits.MAX_BATCH_ITEMS + 1);
            DrawBatchResult result = frame.flush();

            assertAll(
                    () -> assertEquals(255, result.applied()),
                    () -> assertEquals(2, result.dropped()),
                    () -> assertEquals("first", result.firstError()));
        }
    }

    @Nested
    @DisplayName("wire encoding")
    class Encoding {

        @Test
        void everyCommand_carriesAnExplicitTtlAndSpace() {
            when(rpc.callSync(eq(SET), anyMap())).thenReturn(Map.of("key", "k"));
            ArgumentCaptor<Map<String, Object>> params = captor();

            draw.rect("k", 1, 2, 3, 4).submit();

            verify(rpc).callSync(eq(SET), params.capture());
            assertAll(
                    () -> assertEquals(DrawLimits.DEFAULT_TTL_MS, params.getValue().get("ttl_ms")),
                    () -> assertEquals("screen", params.getValue().get("space")),
                    () -> assertEquals("rect", params.getValue().get("kind")));
        }

        @Test
        void worldSpaceHelpers_putWorldOnTheWireRatherThanFailingLocally() {
            when(rpc.callSync(eq(SET), anyMap())).thenReturn(Map.of("key", "t"));
            ArgumentCaptor<Map<String, Object>> params = captor();

            draw.tile("t", 3200, 3200).submit();

            verify(rpc).callSync(eq(SET), params.capture());
            assertAll(
                    () -> assertEquals("world", params.getValue().get("space")),
                    () -> assertEquals(3200 * DrawLimits.SUBTILE_SCALE,
                            params.getValue().get("x")));
        }

        @Test
        void componentHighlight_withoutAKey_usesTheProducersAutoKeyFormat() {
            when(rpc.callSync(eq(SET), anyMap())).thenReturn(Map.of("key", "ignored"));
            ArgumentCaptor<Map<String, Object>> params = captor();

            String key = draw.component(BACKPACK_INTERFACE, BACKPACK_SLOT_COMPONENT)
                    .color(Colors.CYAN)
                    .build()
                    .key();
            draw.component(BACKPACK_INTERFACE, BACKPACK_SLOT_COMPONENT).submit();

            verify(rpc).callSync(eq(SET), params.capture());
            assertAll(
                    () -> assertEquals("comp:1473:5", key),
                    () -> assertEquals("comp:1473:5", params.getValue().get("key")),
                    () -> assertEquals("component", params.getValue().get("kind")),
                    () -> assertEquals(BACKPACK_INTERFACE, params.getValue().get("iface")));
        }

        @Test
        void polyPoints_goOutFlatInTheOrderTheyWereGiven() {
            when(rpc.callSync(eq(SET), anyMap())).thenReturn(Map.of("key", "p"));
            ArgumentCaptor<Map<String, Object>> params = captor();

            draw.poly("p", List.of(1, 2, 3, 4, 5, 6)).closed().submit();

            verify(rpc).callSync(eq(SET), params.capture());
            assertAll(
                    () -> assertEquals(List.of(1, 2, 3, 4, 5, 6), params.getValue().get("points")),
                    () -> assertEquals(true, params.getValue().get("closed")));
        }
    }

    @Nested
    @DisplayName("clear, list, enable, stats")
    class OtherMethods {

        @Test
        void clearAll_neverAsksForAnotherClientsDrawings() {
            when(rpc.callSync(eq(CLEAR_ALL), anyMap())).thenReturn(Map.of("removed", 7));
            ArgumentCaptor<Map<String, Object>> params = captor();

            assertEquals(7, draw.clearAll());

            verify(rpc).callSync(eq(CLEAR_ALL), params.capture());
            assertEquals("mine", params.getValue().get("scope"));
        }

        @Test
        void clear_withNoKeys_spendsNoRoundTrip() {
            assertEquals(0, draw.clear());
            verify(rpc, times(0)).callSync(eq(CLEAR), anyMap());
        }

        @Test
        void clear_withKeys_sendsThemAsOneCall() {
            when(rpc.callSync(eq(CLEAR), anyMap())).thenReturn(Map.of("removed", 2));
            ArgumentCaptor<Map<String, Object>> params = captor();

            assertEquals(2, draw.clear("a", "b"));

            verify(rpc, times(1)).callSync(eq(CLEAR), params.capture());
            assertEquals(List.of("a", "b"), params.getValue().get("keys"));
        }

        /** The wire pages at 128 rows; a store larger than a page must not be truncated. */
        @Test
        void list_pagesUntilTheProducerRunsOut() {
            when(rpc.callSync(eq(LIST), anyMap()))
                    .thenReturn(Map.of("total", 3, "offset", 0, "returned", 2,
                            "items", List.of(row("a"), row("b"))))
                    .thenReturn(Map.of("total", 3, "offset", 2, "returned", 1,
                            "items", List.of(row("c"))));

            List<DrawEntry> entries = draw.list();

            assertAll(
                    () -> verify(rpc, times(2)).callSync(eq(LIST), anyMap()),
                    () -> assertEquals(List.of("a", "b", "c"),
                            entries.stream().map(DrawEntry::key).toList()));
        }

        @Test
        void list_reportsRemainingTtlRatherThanWhatWasSent() {
            when(rpc.callSync(eq(LIST), anyMap())).thenReturn(
                    Map.of("total", 1, "offset", 0, "returned", 1, "items", List.of(row("a"))));

            DrawEntry entry = draw.list().getFirst();

            assertAll(
                    () -> assertEquals(742L, entry.remainingTtlMs()),
                    () -> assertFalse(entry.isPersistent()),
                    () -> assertSame(DrawKind.RECT, entry.kind()),
                    () -> assertSame(DrawSpace.SCREEN, entry.space()));
        }

        @Test
        void isEnabled_readsWithoutWriting() {
            when(rpc.callSync(eq(ENABLE), anyMap())).thenReturn(Map.of("enabled", true));
            ArgumentCaptor<Map<String, Object>> params = captor();

            assertTrue(draw.isEnabled());

            verify(rpc).callSync(eq(ENABLE), params.capture());
            assertFalse(params.getValue().containsKey("enabled"),
                    "omitting the param is what makes this a read");
        }

        @Test
        void stats_decodesTheFieldsWorthAssertingOn() {
            when(rpc.callSync(eq(STATS), anyMap())).thenReturn(Map.ofEntries(
                    Map.entry("count", 3), Map.entry("capacity", DrawLimits.MAX_COMMANDS),
                    Map.entry("text_slots_used", 1),
                    Map.entry("text_slots_capacity", DrawLimits.MAX_TEXT_COMMANDS),
                    Map.entry("poly_slots_used", 0),
                    Map.entry("poly_slots_capacity", DrawLimits.MAX_POLY_COMMANDS),
                    Map.entry("live_targets", 2), Map.entry("dropped", 4L),
                    Map.entry("resolve_failures", 0L), Map.entry("resolve_overflow", 5L),
                    Map.entry("enabled", true), Map.entry("has_presented", true),
                    Map.entry("present_failures", 0L), Map.entry("resolver_live", true),
                    Map.entry("backend_presents", 12L),
                    Map.entry("backend", "layered"), Map.entry("surface", List.of(1920, 1080))));

            DrawStats stats = draw.stats();

            assertAll(
                    () -> assertEquals(3, stats.count()),
                    () -> assertEquals(4L, stats.dropped()),
                    () -> assertEquals(5L, stats.resolveOverflow()),
                    () -> assertTrue(stats.hasPresented()),
                    () -> assertTrue(stats.isResolverLive()),
                    () -> assertEquals("layered", stats.backend()),
                    () -> assertEquals(12L, stats.backendPresents()),
                    () -> assertEquals(1920, stats.surfaceWidth()),
                    () -> assertEquals(1080, stats.surfaceHeight()),
                    () -> assertFalse(stats.isFull()));
        }
    }

    private static Map<String, Object> row(String key) {
        return Map.ofEntries(
                Map.entry("key", key), Map.entry("kind", "rect"), Map.entry("space", "screen"),
                Map.entry("color", Colors.GREEN), Map.entry("thickness", 1), Map.entry("z", 0),
                Map.entry("filled", false), Map.entry("closed", false),
                Map.entry("geom", List.of(1, 2, 3, 4)), Map.entry("resolved", false),
                Map.entry("rect", List.of(0, 0, 0, 0)), Map.entry("ttl_ms", 742),
                Map.entry("text", ""));
    }

    // rule-exception: {rule:no-casts} — Mockito's ArgumentCaptor.forClass cannot
    // express a generic type, so every captor of a parameterised type needs one
    // unchecked conversion. Confined to this helper rather than repeated per test.
    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> captor() {
        return ArgumentCaptor.forClass(Map.class);
    }
}
