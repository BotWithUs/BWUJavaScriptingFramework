package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.model.ComponentChild;
import com.botwithus.bot.api.model.GeOffer;
import com.botwithus.bot.api.model.GeOfferStage;
import com.botwithus.bot.api.model.GeOfferType;
import com.botwithus.bot.api.model.ScreenProjection;
import com.botwithus.bot.api.model.TargetMode;
import com.botwithus.bot.api.model.WorldToScreen;
import com.botwithus.bot.api.world.Coordinate;
import com.botwithus.bot.api.world.HopRefusal;
import com.botwithus.bot.api.world.World;
import com.botwithus.bot.api.world.WorldHopRequest;
import com.botwithus.bot.api.world.WorldList;
import com.botwithus.bot.core.rpc.RpcClient;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The client-state RPCs through {@link GameAPIImpl}, against reply maps shaped exactly as
 * NXTLibrary's PUBLIC_PROTOCOL.md sections 6.2.1, 6.2.2, 6.7 and 6.8 at nxt-library main
 * 0f746b45 spell them. Values are deliberately not the defaults a decoder would produce for
 * a missing key, so a misspelt key fails rather than reading 0 / false / "".
 */
class ClientStateRpcTest {

    private final RpcClient rpc = mock(RpcClient.class);
    private final GameAPIImpl api = new GameAPIImpl(rpc);

    @Nested
    class GeOffers {

        @Test
        void sendsMarketZero_andDecodesEverySlotField() {
            Map<String, Object> live = new HashMap<>();
            live.put("slot", 3);
            live.put("stage", 2);
            live.put("stageName", "added");
            live.put("type", 1);
            live.put("typeName", "sell");
            live.put("itemId", 1515);
            live.put("itemCount", 500);
            live.put("soldCount", 120);
            live.put("price", 5_000_000_000L);
            live.put("goldFromSale", 600_000_000_000L);
            Map<String, Object> empty = Map.of("slot", 0, "stage", 0, "stageName", "empty",
                    "type", 0, "typeName", "buy", "itemId", 0, "itemCount", 0, "soldCount", 0,
                    "price", 0, "goldFromSale", 0);
            when(rpc.callSync("get_ge_offers", Map.of("market", 0)))
                    .thenReturn(Map.of("market", 0, "offers", List.of(empty, live)));

            List<GeOffer> offers = api.getGeOffers();

            GeOffer o = offers.get(1);
            assertAll(
                    () -> assertEquals(2, offers.size()),
                    () -> assertFalse(offers.get(0).isActive(), "stage 0 is the predicate for empty"),
                    () -> assertTrue(o.isActive()),
                    () -> assertEquals(3, o.slot()),
                    () -> assertEquals(GeOfferStage.ADDED, o.stage()),
                    () -> assertEquals("added", o.stageName()),
                    () -> assertEquals(GeOfferType.SELL, o.type()),
                    () -> assertEquals(1515, o.itemId()),
                    () -> assertEquals(500, o.itemCount()),
                    () -> assertEquals(120, o.soldCount()),
                    () -> assertEquals(5_000_000_000L, o.price(), "price can exceed 32 bits"),
                    () -> assertEquals(600_000_000_000L, o.goldFromSale()));
        }

        @Test
        void unlistedStage_isUnknownButStillActive() {
            when(rpc.callSync(eq("get_ge_offers"), anyMap())).thenReturn(Map.of("market", 0,
                    "offers", List.of(Map.of("slot", 0, "stage", 9, "type", 7))));

            GeOffer o = api.getGeOffers().getFirst();

            assertAll(
                    () -> assertTrue(o.isActive()),
                    () -> assertEquals(GeOfferStage.UNKNOWN, o.stage()),
                    () -> assertEquals(9, o.rawStage()),
                    () -> assertEquals(GeOfferType.UNKNOWN, o.type()));
        }
    }

    @Nested
    class Worlds {

        @Test
        void worldList_decodesRowsAndFlags() {
            Map<String, Object> members = Map.of("id", 84, "flags", 0x1 | 0x80 | 0x8,
                    "activity", "Skill total 2600", "locationId", 2, "locationName", "UK",
                    "population", 812, "ping", 0);
            Map<String, Object> eoc = Map.of("id", 3, "flags", 0x800000, "activity", "",
                    "locationId", 0, "locationName", "USA", "population", 34, "ping", 0);
            when(rpc.callSync("get_world_list", Map.of())).thenReturn(Map.of("count", 2,
                    "total", 133, "skipped", 1, "truncated", true, "worlds", List.of(members, eoc)));

            WorldList list = api.getWorldList();

            World w84 = list.find(84).orElseThrow();
            World w3 = list.find(3).orElseThrow();
            assertAll(
                    () -> assertTrue(list.isFetched()),
                    () -> assertEquals(133, list.total()),
                    () -> assertEquals(1, list.skipped()),
                    () -> assertTrue(list.truncated()),
                    () -> assertTrue(w84.isMembers()),
                    () -> assertTrue(w84.isSkillTotal()),
                    () -> assertFalse(w84.isEocOnly()),
                    () -> assertEquals(0x89, w84.flags(), "unresolved bits are kept raw"),
                    () -> assertEquals("Skill total 2600", w84.activity()),
                    () -> assertEquals(2, w84.locationId()),
                    () -> assertEquals("UK", w84.locationName()),
                    () -> assertEquals(812, w84.population()),
                    () -> assertTrue(w3.isEocOnly()),
                    () -> assertFalse(w3.isMembers()),
                    () -> assertFalse(w3.isClassicOnly()));
        }

        @Test
        void emptyWorldList_isNotFetched_notAnError() {
            when(rpc.callSync("get_world_list", Map.of())).thenReturn(Map.of("count", 0,
                    "total", 0, "skipped", 0, "truncated", false, "worlds", List.of()));

            assertFalse(api.getWorldList().isFetched());
        }

        @Test
        void currentWorld_readsWorldId_andMinusOneWhenUnknown() {
            when(rpc.callSync("get_current_world", Map.of())).thenReturn(Map.of("world_id", 84));
            assertEquals(84, api.getCurrentWorld());

            when(rpc.callSync("get_current_world", Map.of())).thenReturn(Map.of("world_id", -1));
            assertEquals(-1, api.getCurrentWorld());
        }

        @Test
        void setWorld_started() {
            when(rpc.callSync("set_world", Map.of("world_id", 84))).thenReturn(Map.of("started", true));

            WorldHopRequest r = api.requestWorld(84);

            assertTrue(r.started());
            assertEquals(Optional.empty(), r.refusal());
        }

        @Test
        void setWorld_refused_carriesReasonAsTheMessage() {
            when(rpc.callSync("set_world", Map.of("world_id", 2)))
                    .thenReturn(Map.of("started", false, "reason", "members_world"));

            WorldHopRequest r = api.requestWorld(2);

            assertFalse(r.started());
            assertEquals("members_world", r.reason());
            assertEquals(Optional.of(HopRefusal.MEMBERS_WORLD), r.refusal());
        }

        @Test
        void setWorld_legacyEmptyReply_isNotStarted() {
            when(rpc.callSync("set_world", Map.of("world_id", 5))).thenReturn(Map.of());

            WorldHopRequest r = api.requestWorld(5);

            assertFalse(r.started(), "an agent before the reply did nothing");
            assertEquals(Optional.of(HopRefusal.UNKNOWN), r.refusal());
        }

        @Test
        void setWorld_nonPositiveId_isRefusedBeforeSending() {
            assertThrows(IllegalArgumentException.class, () -> api.requestWorld(0));
            verify(rpc, never()).callSync(anyString(), anyMap());
        }
    }

    @Nested
    class Target {

        @Test
        void active_decodesSourceAndItem() {
            Map<String, Object> reply = new HashMap<>();
            reply.put("active", true);
            reply.put("isItem", true);
            reply.put("objId", 946);
            reply.put("source", Map.of("iface", 1473, "comp", 5, "sub", 7));
            reply.put("verb", "Use");
            reply.put("name", "<col=ff9040>Knife");
            reply.put("targetMask", 0x2E);
            when(rpc.callSync("get_target_mode", Map.of())).thenReturn(reply);

            TargetMode t = api.getTargetMode();

            assertAll(
                    () -> assertTrue(t.active()),
                    () -> assertTrue(t.isItemSelected(946)),
                    () -> assertFalse(t.isItemSelected(1511)),
                    () -> assertEquals(new TargetMode.Source(1473, 5, 7), t.source().orElseThrow()),
                    () -> assertEquals("Use", t.verb()),
                    () -> assertEquals("<col=ff9040>Knife", t.name()),
                    () -> assertEquals(0x2E, t.targetMask()));
        }

        @Test
        void inactive_readsAsInactive_whateverTheFiller() {
            Map<String, Object> reply = new HashMap<>();
            reply.put("active", false);
            reply.put("isItem", false);
            reply.put("objId", -1);
            reply.put("source", null);
            reply.put("verb", "");
            reply.put("name", "");
            reply.put("targetMask", 0);
            when(rpc.callSync("get_target_mode", Map.of())).thenReturn(reply);

            assertEquals(TargetMode.INACTIVE, api.getTargetMode());
        }
    }

    @Nested
    class Projection {

        @Test
        void sendsPointsWithPlane_andDecodesEachResult() {
            Map<String, Object> behind = new HashMap<>();
            behind.put("result", "behind_camera");
            behind.put("onScreen", false);
            behind.put("x", null);
            behind.put("y", null);
            when(rpc.callSync(eq("world_to_screen"), anyMap())).thenReturn(Map.of(
                    "viewport", Map.of("x", 4, "y", 30, "width", 1200, "height", 700),
                    "points", List.of(
                            Map.of("result", "ok", "onScreen", true, "x", 611, "y", 377),
                            Map.of("result", "off_viewport", "onScreen", false, "x", -40, "y", 900),
                            behind)));

            WorldToScreen w = api.worldToScreen(List.of(new Coordinate(3222, 3218, 0),
                    new Coordinate(3240, 3218, 0), new Coordinate(3200, 3200, 1)));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
            verify(rpc).callSync(eq("world_to_screen"), sent.capture());
            assertEquals(Map.of("x", 3200, "y", 3200, "plane", 1),
                    pointsOf(sent.getValue()).get(2));
            ScreenProjection ok = w.points().get(0);
            ScreenProjection off = w.points().get(1);
            ScreenProjection back = w.points().get(2);
            assertAll(
                    () -> assertEquals(new WorldToScreen.Viewport(4, 30, 1200, 700), w.viewport().orElseThrow()),
                    () -> assertTrue(ok.onScreen()),
                    () -> assertEquals(611, ok.x()),
                    () -> assertEquals(377, ok.y()),
                    () -> assertEquals(ScreenProjection.Result.OFF_VIEWPORT, off.result()),
                    () -> assertTrue(off.hasPixel(), "off_viewport still has real pixels"),
                    () -> assertEquals(-40, off.x()),
                    () -> assertEquals(ScreenProjection.Result.BEHIND_CAMERA, back.result()),
                    () -> assertFalse(back.hasPixel(), "nil x/y must not read as pixel 0,0"));
        }

        @Test
        void nilViewport_isEmpty() {
            Map<String, Object> reply = new HashMap<>();
            reply.put("viewport", null);
            reply.put("points", List.of(Map.of("result", "unavailable", "onScreen", false)));
            when(rpc.callSync(eq("world_to_screen"), anyMap())).thenReturn(reply);

            WorldToScreen w = api.worldToScreen(List.of(new Coordinate(1, 1, 0)));

            assertTrue(w.viewport().isEmpty());
            assertEquals(ScreenProjection.Result.UNAVAILABLE, w.points().getFirst().result());
        }

        @Test
        void emptyOrOverCapBatch_isRefusedBeforeSending() {
            List<Coordinate> tooMany = new ArrayList<>();
            for (int i = 0; i <= WorldToScreen.MAX_POINTS; i++) {
                tooMany.add(new Coordinate(i, i, 0));
            }
            assertThrows(IllegalArgumentException.class, () -> api.worldToScreen(List.of()));
            assertThrows(IllegalArgumentException.class, () -> api.worldToScreen(tooMany));
            verify(rpc, never()).callSync(anyString(), anyMap());
        }

        @SuppressWarnings("unchecked")
        private List<Map<String, Object>> pointsOf(Map<String, Object> params) {
            return (List<Map<String, Object>>) params.get("points");
        }
    }

    @Nested
    class Children {

        @Test
        void dynamicSlots_sendWithSub_andDecodeCompAndSub() {
            when(rpc.callSync("get_dynamic_children", Map.of("iface", 1473, "comp", 5, "withSub", true)))
                    .thenReturn(Map.of("children", List.of(Map.of("comp", 5, "sub", 0),
                            Map.of("comp", 5, "sub", 27))));

            List<ComponentChild> kids = api.getDynamicChildSlots(1473, 5);

            assertEquals(List.of(new ComponentChild(5, 0), new ComponentChild(5, 27)), kids);
        }

        @Test
        void staticSlots_unsetSub_isMinusOne() {
            when(rpc.callSync("get_static_children", Map.of("iface", 1477, "comp", 12, "withSub", true)))
                    .thenReturn(Map.of("children", List.of(Map.of("comp", 13, "sub", -1))));

            ComponentChild c = api.getStaticChildSlots(1477, 12).getFirst();

            assertEquals(13, c.componentId());
            assertFalse(c.hasSub());
        }
    }

    @Nested
    class ExecuteScript {

        @Test
        void stringArgs_areSentInOrder() {
            when(rpc.callSync(eq("execute_script"), anyMap())).thenReturn(Map.of("returns", List.of(7)));

            api.executeScript(99L, new int[] {4}, new String[] {"Lumbridge", "abc"}, new String[] {"int"});

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
            verify(rpc).callSync(eq("execute_script"), sent.capture());
            assertAll(
                    () -> assertEquals(99L, sent.getValue().get("handle")),
                    () -> assertEquals(List.of(4), sent.getValue().get("int_args")),
                    () -> assertEquals(List.of("Lumbridge", "abc"), sent.getValue().get("string_args")),
                    () -> assertEquals(List.of("int"), sent.getValue().get("returns")));
        }

        @Test
        void sixteenArgsPass_seventeenAreRefused() {
            when(rpc.callSync(eq("execute_script"), anyMap())).thenReturn(Map.of("returns", List.of()));
            api.executeScript(1L, new int[10], new String[] {"a", "b", "c", "d", "e", "f"}, null);

            assertThrows(IllegalArgumentException.class,
                    () -> api.executeScript(1L, new int[11], new String[] {"a", "b", "c", "d", "e", "f"}, null));
        }

        @Test
        void stringOver255Utf8Bytes_isRefused_evenWhenUnder255Chars() {
            when(rpc.callSync(eq("execute_script"), anyMap())).thenReturn(Map.of("returns", List.of()));
            api.executeScript(1L, null, new String[] {"x".repeat(255)}, null);

            String twoBytesEach = "é".repeat(128);
            assertThrows(IllegalArgumentException.class,
                    () -> api.executeScript(1L, null, new String[] {twoBytesEach}, null));
        }
    }
}
