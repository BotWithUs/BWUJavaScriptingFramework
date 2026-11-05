package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.model.EntityKind;
import com.botwithus.bot.api.model.EntityRead;
import com.botwithus.bot.api.model.EntityReadStatus;
import com.botwithus.bot.api.model.Headbar;
import com.botwithus.bot.api.model.HintArrow;
import com.botwithus.bot.api.model.HintArrowType;
import com.botwithus.bot.api.model.OverheadText;
import com.botwithus.bot.core.msgpack.MessagePackCodec;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.rpc.RpcException;
import com.botwithus.bot.core.rpc.RpcRemoteException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code get_overhead_text}, {@code get_headbars} and {@code query_hint_arrows} through
 * {@link GameAPIImpl}. Reply shapes are NXTLibrary's PUBLIC_PROTOCOL.md section 6.2 at 412510a.
 */
class EntityReadsDecodeTest {

    private static final String OVERHEAD = "get_overhead_text";
    private static final String HEADBARS = "get_headbars";
    private static final String ARROWS = "query_hint_arrows";
    private static final int SERVER_INDEX = 42;
    private static final Map<String, Object> NPC_TARGET = Map.of("targetType", "npc", "serverIndex", SERVER_INDEX);
    private static final Map<String, Object> PLAYER_TARGET =
            Map.of("targetType", "player", "serverIndex", SERVER_INDEX);

    private final RpcClient rpc = mock(RpcClient.class);
    private final GameAPIImpl api = new GameAPIImpl(rpc);

    // ---------------- get_overhead_text ----------------

    @Test
    void overheadText_speaking_decodesTextAndExpiry() {
        when(rpc.callSync(OVERHEAD, NPC_TARGET)).thenReturn(Map.of("text", "Cheep!", "expiresInMs", 1500));

        EntityRead<Optional<OverheadText>> read = api.getOverheadText(EntityKind.NPC, SERVER_INDEX);

        assertTrue(read.isAvailable());
        assertEquals(Optional.of(new OverheadText("Cheep!", 1500)), read.value());
        assertFalse(read.isTruncated(), "an absent truncated key reads false");
    }

    @Test
    void overheadText_truncated_isAFlagOnAGoodReading() {
        when(rpc.callSync(OVERHEAD, NPC_TARGET)).thenReturn(
                Map.of("text", "Cheep", "expiresInMs", 1500, "truncated", true));

        EntityRead<Optional<OverheadText>> read = api.getOverheadText(EntityKind.NPC, SERVER_INDEX);

        assertTrue(read.isAvailable());
        assertTrue(read.isTruncated());
        assertEquals("Cheep", read.value().orElseThrow().text());
    }

    /** The agent writes {@code {text: nil}}; a getString-based check would see "" and call it speech. */
    @Test
    void overheadText_nilText_isSilentAndAvailable() {
        Map<String, Object> silent = new HashMap<>();
        silent.put("text", null);
        when(rpc.callSync(OVERHEAD, PLAYER_TARGET)).thenReturn(silent);

        EntityRead<Optional<OverheadText>> read = api.getOverheadText(EntityKind.PLAYER, SERVER_INDEX);

        assertTrue(read.isAvailable(), "silent is an answer, not a refusal");
        assertTrue(read.value().isEmpty());
        assertFalse(read.isTruncated());
    }

    /** The other half of the nil trap: the codec must keep a nil value as a present null. */
    @Test
    void overheadText_nilSurvivesTheCodec() {
        Map<String, Object> silent = new LinkedHashMap<>();
        silent.put("text", null);

        Map<String, Object> decoded = MessagePackCodec.decode(MessagePackCodec.encode(silent));

        assertTrue(decoded.containsKey("text"));
        assertNull(decoded.get("text"));
    }

    @ParameterizedTest
    @CsvSource({
            "not_in_world, NOT_IN_WORLD",
            "not_found, NOT_FOUND",
            "channel_unsupported, CHANNEL_UNSUPPORTED",
            "game_thread_timeout, GAME_THREAD_TIMEOUT",
            "something_new, UNRECOGNISED",
            "NOT_FOUND, UNRECOGNISED",
    })
    void refusal_mapsToItsStatus_inAllThreeReads(String wire, EntityReadStatus expected) {
        when(rpc.callSync(eq(OVERHEAD), anyMap())).thenThrow(new RpcRemoteException(OVERHEAD, wire));
        when(rpc.callSync(eq(HEADBARS), anyMap())).thenThrow(new RpcRemoteException(HEADBARS, wire));
        when(rpc.callSync(eq(ARROWS), anyMap())).thenThrow(new RpcRemoteException(ARROWS, wire));

        EntityRead<Optional<OverheadText>> text = api.getOverheadText(EntityKind.NPC, SERVER_INDEX);
        EntityRead<List<Headbar>> bars = api.getHeadbars(EntityKind.NPC, SERVER_INDEX);
        EntityRead<List<HintArrow>> arrows = api.queryHintArrows();

        assertEquals(expected, text.status());
        assertEquals(wire, text.refusalText(), "the agent's words are kept");
        assertEquals(wire, bars.refusalText());
        assertEquals(wire, arrows.refusalText());
        assertTrue(text.value().isEmpty());
        assertEquals(expected, bars.status());
        assertTrue(bars.value().isEmpty());
        assertEquals(expected, arrows.status());
        assertTrue(arrows.value().isEmpty());
        assertFalse(text.isTruncated() || bars.isTruncated() || arrows.isTruncated());
    }

    @Test
    void anAnswer_hasNoRefusalText() {
        when(rpc.callSync(HEADBARS, NPC_TARGET)).thenReturn(Map.of("headbars", List.of(), "truncated", false));

        assertEquals("", api.getHeadbars(EntityKind.NPC, SERVER_INDEX).refusalText());
    }

    /** What a mismatched agent build sends; the text is the only thing that explains it. */
    @Test
    void unrecognisedRefusal_keepsTheAgentsWords() {
        String agentSays = "method not found: get_headbars";
        when(rpc.callSync(HEADBARS, NPC_TARGET)).thenThrow(new RpcRemoteException(HEADBARS, agentSays));

        EntityRead<List<Headbar>> read = api.getHeadbars(EntityKind.NPC, SERVER_INDEX);

        assertEquals(EntityReadStatus.UNRECOGNISED, read.status());
        assertEquals(agentSays, read.refusalText());
    }

    /** The agent writes 64 bits, so a large expiry must not wrap to a negative or a small number. */
    @Test
    void overheadText_expiryKeepsAllSixtyFourBits() {
        long beyondAnInt = (long) Integer.MAX_VALUE + 1000L;
        when(rpc.callSync(OVERHEAD, NPC_TARGET)).thenReturn(
                Map.of("text", "Cheep!", "expiresInMs", beyondAnInt, "truncated", false));

        assertEquals(beyondAnInt, api.getOverheadText(EntityKind.NPC, SERVER_INDEX)
                .value().orElseThrow().expiresInMs());
    }

    @Test
    void argumentRefusal_throwsRatherThanBecomingAStatus() {
        when(rpc.callSync(OVERHEAD, NPC_TARGET)).thenThrow(new RpcRemoteException(OVERHEAD,
                "get_overhead_text: needs targetType npc|player and an integer serverIndex"));
        when(rpc.callSync(HEADBARS, NPC_TARGET)).thenThrow(new RpcRemoteException(HEADBARS,
                "get_headbars: needs targetType npc|player and an integer serverIndex"));

        assertThrows(IllegalArgumentException.class, () -> api.getOverheadText(EntityKind.NPC, SERVER_INDEX));
        assertThrows(IllegalArgumentException.class, () -> api.getHeadbars(EntityKind.NPC, SERVER_INDEX));
    }

    @Test
    void transportFailure_propagates() {
        when(rpc.callSync(eq(OVERHEAD), anyMap())).thenThrow(new RpcException("pipe closed"));
        when(rpc.callSync(eq(HEADBARS), anyMap())).thenThrow(new RpcException("pipe closed"));
        when(rpc.callSync(eq(ARROWS), anyMap())).thenThrow(new RpcException("pipe closed"));

        assertThrows(RpcException.class, () -> api.getOverheadText(EntityKind.NPC, SERVER_INDEX));
        assertThrows(RpcException.class, () -> api.getHeadbars(EntityKind.NPC, SERVER_INDEX));
        assertThrows(RpcException.class, api::queryHintArrows);
    }

    @Test
    void everyStatusRoundTripsThroughItsWireCode() {
        for (EntityReadStatus status : EntityReadStatus.values()) {
            if (status != EntityReadStatus.UNRECOGNISED) {
                assertEquals(status, EntityReadStatus.fromWireCode(status.wireCode()));
            }
        }
        assertEquals(EntityReadStatus.UNRECOGNISED, EntityReadStatus.fromWireCode(null));
        assertEquals(EntityReadStatus.UNRECOGNISED, EntityReadStatus.fromWireCode(""));
    }

    // ---------------- get_headbars ----------------

    @Test
    void headbars_decodeEveryFieldInOrder() {
        when(rpc.callSync(HEADBARS, PLAYER_TARGET)).thenReturn(Map.of("truncated", false, "headbars", List.of(
                Map.of("headbarId", 7, "value", 200, "valueStart", 100, "startCycle", 12345, "duration", 30),
                Map.of("headbarId", 0, "value", 255, "valueStart", 255, "startCycle", 12346, "duration", 0))));

        EntityRead<List<Headbar>> read = api.getHeadbars(EntityKind.PLAYER, SERVER_INDEX);

        assertTrue(read.isAvailable());
        assertEquals(List.of(
                new Headbar(Headbar.ADRENALINE_DEFAULT, 200, 100, 12345, 30),
                new Headbar(Headbar.HEALTH_DEFAULT, 255, 255, 12346, 0)), read.value());
    }

    @Test
    void headbars_none_isAnEmptyAvailableList() {
        when(rpc.callSync(HEADBARS, NPC_TARGET)).thenReturn(Map.of("headbars", List.of(), "truncated", false));

        EntityRead<List<Headbar>> read = api.getHeadbars(EntityKind.NPC, SERVER_INDEX);

        assertTrue(read.isAvailable());
        assertTrue(read.value().isEmpty());
    }

    @Test
    void headbars_truncated_keepsTheRowsAndRaisesTheFlag() {
        when(rpc.callSync(HEADBARS, NPC_TARGET)).thenReturn(Map.of("truncated", true, "headbars", List.of(
                Map.of("headbarId", 0, "value", 1, "valueStart", 1, "startCycle", 1, "duration", 0))));

        EntityRead<List<Headbar>> read = api.getHeadbars(EntityKind.NPC, SERVER_INDEX);

        assertTrue(read.isTruncated());
        assertEquals(1, read.value().size());
    }

    @Test
    void headbars_absentTruncatedKey_readsFalse() {
        when(rpc.callSync(HEADBARS, NPC_TARGET)).thenReturn(Map.of("headbars", List.of()));

        assertFalse(api.getHeadbars(EntityKind.NPC, SERVER_INDEX).isTruncated());
    }

    @Test
    void headbar_percent_isOfTheFullWireScale() {
        assertEquals(100.0, bar(255).percent());
        assertEquals(0.0, bar(0).percent());
        assertEquals(20.0, bar(51).percent());
        assertTrue(bar(1).percent() > 0.0, "a nearly empty bar is not an empty bar");
        assertEquals(100.0, bar(300).percent(), "held to the promised range");
        assertEquals(0.0, bar(-1).percent(), "held to the promised range");
    }

    // ---------------- query_hint_arrows ----------------

    @Test
    void hintArrow_treeTileArrow_decodesTheLiveFixture() {
        when(rpc.callSync(eq(ARROWS), anyMap())).thenReturn(arrowsReply(treeCornerArrow()));

        HintArrow arrow = api.queryHintArrows().value().getFirst();

        assertEquals(HintArrow.Source.TREE, arrow.source());
        assertEquals(HintArrow.NO_VALUE, arrow.slot());
        assertEquals(HintArrowType.TILE_CORNER, arrow.type());
        assertEquals(7, arrow.rawType());
        assertEquals(HintArrow.NO_VALUE, arrow.targetIndex());
        assertEquals(0, arrow.plane());
        assertEquals(3209, arrow.tileX());
        assertEquals(3215, arrow.tileY());
        assertEquals(150, arrow.rangeTiles());
        assertTrue(arrow.hasTile());
    }

    @Test
    void hintArrow_npcArrow_hasATargetAndNoTile() {
        when(rpc.callSync(eq(ARROWS), anyMap())).thenReturn(arrowsReply(entityArrow(1, "npc", SERVER_INDEX, 1, 1)));

        HintArrow arrow = api.queryHintArrows().value().getFirst();

        assertEquals(HintArrow.Source.SLOT, arrow.source());
        assertEquals(HintArrowType.NPC, arrow.type());
        assertEquals(SERVER_INDEX, arrow.targetIndex());
        assertFalse(arrow.hasTile(), "an entity arrow has no usable tile even when the field holds a number");
    }

    @Test
    void hintArrow_tileArrowWhosePositionIsNotACoordinate_hasNoTile() {
        Map<String, Object> m = new HashMap<>(treeCornerArrow());
        m.put("tileX", -1);
        when(rpc.callSync(eq(ARROWS), anyMap())).thenReturn(arrowsReply(m));

        assertFalse(api.queryHintArrows().value().getFirst().hasTile());
    }

    @Test
    void hintArrow_unlistedType_keepsTheRawCode() {
        when(rpc.callSync(eq(ARROWS), anyMap())).thenReturn(arrowsReply(entityArrow(99, "unknown", -1, -1, -1)));

        HintArrow arrow = api.queryHintArrows().value().getFirst();

        assertEquals(HintArrowType.UNKNOWN, arrow.type());
        assertEquals(99, arrow.rawType());
        assertFalse(arrow.hasTile());
    }

    @Test
    void hintArrows_truncated_isAFlagOnAGoodReading() {
        when(rpc.callSync(eq(ARROWS), anyMap())).thenReturn(
                Map.of("arrows", List.of(treeCornerArrow()), "truncated", true));

        EntityRead<List<HintArrow>> read = api.queryHintArrows();

        assertTrue(read.isTruncated());
        assertEquals(1, read.value().size());
    }

    /** Every code in the doc's table, so a renumbered enum fails rather than misnaming an arrow. */
    @ParameterizedTest
    @CsvSource({
            "1, NPC, false", "2, TILE_CENTRE, true", "3, TILE_WEST, true", "4, TILE_EAST, true",
            "5, TILE_SOUTH, true", "6, TILE_NORTH, true", "7, TILE_CORNER, true", "10, PLAYER, false",
            "8, UNKNOWN, false", "-1, UNKNOWN, false",
    })
    void hintArrowType_matchesTheWireTable(int code, HintArrowType expected, boolean isTile) {
        assertEquals(expected, HintArrowType.fromWireCode(code));
        assertEquals(isTile, expected.isTile());
    }

    private static Map<String, Object> arrowsReply(Map<String, Object> arrow) {
        return Map.of("arrows", List.of(arrow), "truncated", false);
    }

    private static Headbar bar(int value) {
        return new Headbar(Headbar.HEALTH_DEFAULT, value, value, 0, 0);
    }

    private static Map<String, Object> treeCornerArrow() {
        Map<String, Object> m = new HashMap<>();
        m.put("source", "tree");
        m.put("slot", -1);
        m.put("type", 7);
        m.put("typeName", "tile_corner");
        m.put("targetType", "tile");
        m.put("targetIndex", -1);
        m.put("plane", 0);
        m.put("tileX", 3209);
        m.put("tileY", 3215);
        m.put("rangeTiles", 150);
        return m;
    }

    private static Map<String, Object> entityArrow(int type, String target, int index, int tileX, int tileY) {
        Map<String, Object> m = new HashMap<>();
        m.put("source", "slot");
        m.put("slot", 0);
        m.put("type", type);
        m.put("typeName", target);
        m.put("targetType", target);
        m.put("targetIndex", index);
        m.put("plane", 0);
        m.put("tileX", tileX);
        m.put("tileY", tileY);
        m.put("rangeTiles", 0);
        return m;
    }
}
