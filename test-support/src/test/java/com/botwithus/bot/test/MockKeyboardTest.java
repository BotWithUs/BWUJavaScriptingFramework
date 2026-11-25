package com.botwithus.bot.test;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.input.FocusMode;
import com.botwithus.bot.api.input.FocusState;
import com.botwithus.bot.api.input.KeyResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The keyboard seams of {@link MockScriptContext}: recorded keys, focus modes, and the focus refusal. */
class MockKeyboardTest {

    private static final int VK_1 = 0x31;
    private static final int VK_2 = 0x32;
    private static final int HOLD_MS = 80;

    @Test
    void sendKey_byDefault_isSentAndRecordedInOrder() {
        List<String> keys = new ArrayList<>();
        GameAPI api = MockScriptContext.builder().recordingKeysInto(keys).build().getGameAPI();

        assertAll(
                () -> assertEquals(KeyResult.SENT, api.sendKey(VK_1)),
                () -> assertEquals(KeyResult.SENT, api.sendKey(VK_2, HOLD_MS)));
        assertEquals(List.of("49/0", "50/80"), keys);
    }

    @Test
    void sendKey_realModeWithoutRealFocus_isNotFocused_andNotRecorded() {
        List<String> keys = new ArrayList<>();
        GameAPI api = MockScriptContext.builder().recordingKeysInto(keys).withRealFocus(false).build().getGameAPI();

        assertEquals(KeyResult.NOT_FOCUSED, api.sendKey(VK_1));
        assertTrue(keys.isEmpty());
    }

    @Test
    void sendKey_agentModeWithoutRealFocus_isSent() {
        List<String> keys = new ArrayList<>();
        GameAPI api = MockScriptContext.builder().recordingKeysInto(keys).withRealFocus(false).build().getGameAPI();

        api.setFocusMode(FocusMode.AGENT);

        assertEquals(KeyResult.SENT, api.sendKey(VK_1));
        assertEquals(List.of("49/0"), keys);
    }

    @Test
    void sendKey_outOfRange_throws() {
        GameAPI api = MockScriptContext.builder().build().getGameAPI();

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> api.sendKey(-1)),
                () -> assertThrows(IllegalArgumentException.class, () -> api.sendKey(256)),
                () -> assertThrows(IllegalArgumentException.class, () -> api.sendKey(VK_1, -1)),
                () -> assertThrows(IllegalArgumentException.class, () -> api.sendKey(VK_1, 5001)));
    }

    @Test
    void setFocusMode_recordsEachAppliedMode_andFocusModeReadsIt() {
        List<FocusMode> modes = new ArrayList<>();
        GameAPI api = MockScriptContext.builder().recordingFocusModesInto(modes).build().getGameAPI();

        assertEquals(new FocusState(FocusMode.AGENT, true, true, true), api.setFocusMode(FocusMode.AGENT));
        assertEquals(FocusMode.AGENT, api.focusMode().mode());
        api.setFocusMode(FocusMode.REAL);

        assertEquals(List.of(FocusMode.AGENT, FocusMode.REAL), modes);
    }

    @Test
    void setFocusMode_agentUnavailable_keepsModeAndReportsUnavailable() {
        List<FocusMode> modes = new ArrayList<>();
        GameAPI api = MockScriptContext.builder()
                .recordingFocusModesInto(modes).withAgentFocusAvailable(false).build().getGameAPI();

        assertEquals(new FocusState(FocusMode.REAL, false, false, true), api.setFocusMode(FocusMode.AGENT));
        assertTrue(modes.isEmpty());
    }

    @Test
    void withFocusMode_setsTheStartingMode() {
        GameAPI api = MockScriptContext.builder().withFocusMode(FocusMode.AGENT).build().getGameAPI();

        assertEquals(FocusMode.AGENT, api.focusMode().mode());
    }
}
