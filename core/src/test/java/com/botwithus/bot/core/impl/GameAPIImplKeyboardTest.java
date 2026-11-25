package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.input.FocusMode;
import com.botwithus.bot.api.input.FocusState;
import com.botwithus.bot.api.input.KeyResult;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.rpc.RpcException;
import com.botwithus.bot.core.rpc.RpcRemoteException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The {@code send_key} / {@code set_focus_mode} / {@code get_focus_mode} binding:
 * the exact params put on the wire, and how each agent reply or handler error
 * maps to a result.
 *
 * <p>Error texts are the agent's own literals ({@code Handlers.cpp}
 * {@code Handle_SendKey} / {@code Handle_SetFocusMode}), written out here rather
 * than taken from the code under test.</p>
 */
class GameAPIImplKeyboardTest {

    private static final String SEND_KEY = "send_key";
    private static final String SET_FOCUS_MODE = "set_focus_mode";
    private static final String GET_FOCUS_MODE = "get_focus_mode";

    /** {@code VK_1}. */
    private static final int VK_1 = 0x31;
    private static final int HOLD_MS = 120;

    private RpcClient rpc;
    private GameAPIImpl api;

    @BeforeEach
    void build() {
        rpc = mock(RpcClient.class);
        GameSnapshot snap = mock(GameSnapshot.class);
        api = new GameAPIImpl(rpc, null, () -> snap);
    }

    private void sendKeyReplies(boolean sentVk) {
        when(rpc.callSync(eq(SEND_KEY), anyMap())).thenReturn(Map.of("sent_vk", sentVk, "sent_char", false));
    }

    private void sendKeyRefuses(String error) {
        when(rpc.callSync(eq(SEND_KEY), anyMap())).thenThrow(new RpcRemoteException(SEND_KEY, error));
    }

    private void focusReads(String mode, boolean armed, boolean available, boolean realFocus) {
        when(rpc.callSync(eq(GET_FOCUS_MODE), anyMap())).thenReturn(Map.of(
                "mode", mode, "armed", armed, "available", available,
                "real_focus_on_view", realFocus, "os_foreground", 0x10L, "os_focus", 0x20L));
    }

    @Nested
    class SendKey {

        @Test
        void sendKey_noHold_sendsVkAndZeroHoldOnly() {
            sendKeyReplies(true);

            api.sendKey(VK_1);

            verify(rpc).callSync(SEND_KEY, Map.of("vk", VK_1, "hold_ms", 0));
        }

        @Test
        void sendKey_withHold_sendsVkAndHold_neverCharOrExtended() {
            sendKeyReplies(true);

            api.sendKey(VK_1, HOLD_MS);

            verify(rpc).callSync(SEND_KEY, Map.of("vk", VK_1, "hold_ms", HOLD_MS));
        }

        @Test
        void sendKey_sentVkTrue_isSent() {
            sendKeyReplies(true);

            assertEquals(KeyResult.SENT, api.sendKey(VK_1));
        }

        @Test
        void sendKey_sentVkFalse_isNotSent() {
            sendKeyReplies(false);

            assertEquals(KeyResult.NOT_SENT, api.sendKey(VK_1));
        }

        @ParameterizedTest
        @CsvSource({"not_focused, NOT_FOCUSED", "no_window, NO_WINDOW", "focus_lost, FOCUS_LOST"})
        void sendKey_focusRefusal_isAResult(String error, KeyResult expected) {
            sendKeyRefuses(error);

            assertEquals(expected, api.sendKey(VK_1, HOLD_MS));
        }

        @Test
        void sendKey_otherRefusal_propagates() {
            RpcRemoteException refusal = new RpcRemoteException(SEND_KEY, "send_key: bad_param vk");
            when(rpc.callSync(eq(SEND_KEY), anyMap())).thenThrow(refusal);

            assertSame(refusal, assertThrows(RpcRemoteException.class, () -> api.sendKey(VK_1)));
        }

        @Test
        void sendKey_transportFailure_propagates() {
            when(rpc.callSync(eq(SEND_KEY), anyMap())).thenThrow(new RpcException("pipe gone"));

            assertThrows(RpcException.class, () -> api.sendKey(VK_1));
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 255})
        void sendKey_vkAtBounds_isSent(int vk) {
            sendKeyReplies(true);

            assertEquals(KeyResult.SENT, api.sendKey(vk));
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, 256})
        void sendKey_vkOutOfRange_throwsWithoutACall(int vk) {
            assertThrows(IllegalArgumentException.class, () -> api.sendKey(vk));
            verify(rpc, never()).callSync(anyString(), anyMap());
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 5000})
        void sendKey_holdAtBounds_isSent(int holdMs) {
            sendKeyReplies(true);

            assertEquals(KeyResult.SENT, api.sendKey(VK_1, holdMs));
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, 5001})
        void sendKey_holdOutOfRange_throwsWithoutACall(int holdMs) {
            assertThrows(IllegalArgumentException.class, () -> api.sendKey(VK_1, holdMs));
            verify(rpc, never()).callSync(anyString(), anyMap());
        }
    }

    @Nested
    class FocusModeRead {

        @Test
        void focusMode_sendsNoParams_andDecodesEveryField() {
            focusReads("agent", true, true, false);

            FocusState state = api.focusMode();

            verify(rpc).callSync(GET_FOCUS_MODE, Map.of());
            assertEquals(new FocusState(FocusMode.AGENT, true, true, false), state);
        }

        @Test
        void focusMode_real_decodesReal() {
            focusReads("real", false, true, true);

            assertEquals(new FocusState(FocusMode.REAL, false, true, true), api.focusMode());
        }

        @Test
        void focusMode_unknownModeText_fallsBackToArmed() {
            focusReads("later", true, true, true);

            assertEquals(FocusMode.AGENT, api.focusMode().mode());
        }
    }

    @Nested
    class SetFocusMode {

        @ParameterizedTest
        @CsvSource({"AGENT, agent", "REAL, real"})
        void setFocusMode_sendsModeName_thenReadsBack(FocusMode mode, String wire) {
            when(rpc.callSync(eq(SET_FOCUS_MODE), anyMap()))
                    .thenReturn(Map.of("mode", wire, "armed", mode == FocusMode.AGENT, "available", true));
            focusReads(wire, mode == FocusMode.AGENT, true, false);

            FocusState state = api.setFocusMode(mode);

            InOrder order = inOrder(rpc);
            order.verify(rpc).callSync(SET_FOCUS_MODE, Map.of("mode", wire));
            order.verify(rpc).callSync(GET_FOCUS_MODE, Map.of());
            assertEquals(new FocusState(mode, mode == FocusMode.AGENT, true, false), state);
        }

        @Test
        void setFocusMode_notAvailable_returnsCurrentState() {
            when(rpc.callSync(eq(SET_FOCUS_MODE), anyMap()))
                    .thenThrow(new RpcRemoteException(SET_FOCUS_MODE, "set_focus_mode: not_available"));
            focusReads("real", false, false, false);

            assertEquals(new FocusState(FocusMode.REAL, false, false, false), api.setFocusMode(FocusMode.AGENT));
        }

        @Test
        void setFocusMode_swapFailed_returnsTheStateReadBack() {
            when(rpc.callSync(eq(SET_FOCUS_MODE), anyMap()))
                    .thenThrow(new RpcRemoteException(SET_FOCUS_MODE, "set_focus_mode: swap_failed"));
            focusReads("agent", true, true, false);

            FocusState state = api.setFocusMode(FocusMode.REAL);

            verify(rpc).callSync(GET_FOCUS_MODE, Map.of());
            assertEquals(new FocusState(FocusMode.AGENT, true, true, false), state);
        }

        @Test
        void setFocusMode_otherRefusal_propagatesWithoutReadBack() {
            when(rpc.callSync(eq(SET_FOCUS_MODE), anyMap()))
                    .thenThrow(new RpcRemoteException(SET_FOCUS_MODE, "method not found: set_focus_mode"));

            assertThrows(RpcRemoteException.class, () -> api.setFocusMode(FocusMode.AGENT));
            verify(rpc, never()).callSync(eq(GET_FOCUS_MODE), anyMap());
        }

        @Test
        void setFocusMode_null_throwsWithoutACall() {
            assertThrows(NullPointerException.class, () -> api.setFocusMode(null));
            verify(rpc, never()).callSync(anyString(), anyMap());
        }
    }
}
