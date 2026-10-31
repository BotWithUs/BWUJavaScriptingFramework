package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.model.AppearanceSlot;
import com.botwithus.bot.api.model.AppearanceStatus;
import com.botwithus.bot.api.model.PlayerAppearance;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.rpc.RpcException;
import com.botwithus.bot.core.rpc.RpcRemoteException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code get_player_appearance} through {@link GameAPIImpl#getPlayerAppearance(int)}. */
class PlayerAppearanceDecodeTest {

    private static final String METHOD = "get_player_appearance";
    private static final int SERVER_INDEX = 42;
    private static final int RENDER_ID = 2699;
    private static final int SHORTBOW = 841;
    private static final int SHORTBOW_CLIENT_SLOT = 15;
    private static final int KIT = 3;
    private static final int SLOT_COUNT = 19;
    private static final List<Integer> COLOURS = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

    @Test
    void okReply_decodesSlotsInClientOrderAndTheRawGender() {
        RpcClient rpc = mock(RpcClient.class);
        when(rpc.callSync(eq(METHOD), eq(Map.of("serverIndex", SERVER_INDEX)))).thenReturn(Map.of(
                "render_id", RENDER_ID,
                "npc_transform_id", -1,
                "gender", 1,
                "slots", slotsWithShortbowAtClientSlot15(),
                "colours", COLOURS));

        PlayerAppearance a = new GameAPIImpl(rpc).getPlayerAppearance(SERVER_INDEX);

        assertTrue(a.isAvailable());
        assertEquals(RENDER_ID, a.renderId());
        assertFalse(a.isTransformed());
        assertEquals(1, a.gender());
        assertEquals(SLOT_COUNT, a.slots().size());
        assertEquals(new AppearanceSlot(-1, SHORTBOW), a.slots().get(SHORTBOW_CLIENT_SLOT),
                "slots keep the client's order; wearpos 3 is not index 3");
        assertTrue(a.isWearing(SHORTBOW));
        assertTrue(a.slots().get(0).hasKit());
        assertEquals(COLOURS, a.colours());
        verify(rpc).callSync(eq(METHOD), eq(Map.of("serverIndex", SERVER_INDEX)));
    }

    /** Each agent refusal string, exactly as the agent spells it, maps to its own status. */
    @ParameterizedTest
    @CsvSource({
            "not_in_game, NOT_IN_GAME",
            "no_player, NO_PLAYER",
            "appearance_not_loaded, APPEARANCE_NOT_LOADED",
            "bad_appearance_record, BAD_APPEARANCE_RECORD",
            "unsupported_on_client_channel, UNSUPPORTED_ON_CLIENT_CHANNEL",
            "game_thread_timeout, GAME_THREAD_TIMEOUT",
            "something_new, UNRECOGNISED",
            "NOT_IN_GAME, UNRECOGNISED",
    })
    void refusal_mapsToItsStatus_withNoData(String wire, AppearanceStatus expected) {
        RpcClient rpc = mock(RpcClient.class);
        when(rpc.callSync(eq(METHOD), eq(Map.of("serverIndex", SERVER_INDEX))))
                .thenThrow(new RpcRemoteException(METHOD, wire));

        PlayerAppearance a = new GameAPIImpl(rpc).getPlayerAppearance(SERVER_INDEX);

        assertEquals(expected, a.status());
        assertFalse(a.isAvailable());
        assertTrue(a.slots().isEmpty());
        assertEquals(PlayerAppearance.UNKNOWN, a.renderId());
    }

    @Test
    void argumentRefusal_throwsRatherThanBecomingAStatus() {
        RpcClient rpc = mock(RpcClient.class);
        when(rpc.callSync(eq(METHOD), eq(Map.of("serverIndex", SERVER_INDEX))))
                .thenThrow(new RpcRemoteException(METHOD, "get_player_appearance: needs an integer serverIndex"));

        assertThrows(IllegalArgumentException.class,
                () -> new GameAPIImpl(rpc).getPlayerAppearance(SERVER_INDEX));
    }

    @Test
    void transportFailure_propagates() {
        RpcClient rpc = mock(RpcClient.class);
        when(rpc.callSync(eq(METHOD), eq(Map.of("serverIndex", SERVER_INDEX))))
                .thenThrow(new RpcException("pipe closed"));

        assertThrows(RpcException.class, () -> new GameAPIImpl(rpc).getPlayerAppearance(SERVER_INDEX));
    }

    @Test
    void everyStatusRoundTripsThroughItsWireCode() {
        for (AppearanceStatus status : AppearanceStatus.values()) {
            if (status != AppearanceStatus.UNRECOGNISED) {
                assertEquals(status, AppearanceStatus.fromWireCode(status.wireCode()));
            }
        }
        assertEquals(AppearanceStatus.UNRECOGNISED, AppearanceStatus.fromWireCode(null));
        assertEquals(AppearanceStatus.UNRECOGNISED, AppearanceStatus.fromWireCode(""));
    }

    private static List<Map<String, Object>> slotsWithShortbowAtClientSlot15() {
        List<Map<String, Object>> slots = new ArrayList<>();
        for (int i = 0; i < SLOT_COUNT; i++) {
            slots.add(i == SHORTBOW_CLIENT_SLOT
                    ? Map.of("kit", -1, "item", SHORTBOW)
                    : Map.of("kit", KIT, "item", -1));
        }
        return slots;
    }
}
