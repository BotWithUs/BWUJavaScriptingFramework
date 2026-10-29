package com.botwithus.bot.cli.gui.inspector;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Recording the interact keys from what the user presses. */
class InteractKeysRecorderTest {

    private static final int VK_Q = 'Q';
    private static final Set<Integer> NONE = Set.of();

    private final InteractKeysRecorder recorder = new InteractKeysRecorder();

    @Test
    void keysPressedInTurn_countTogether_onceAllAreLetGo() {
        recorder.start();

        Optional<InteractKeys> whileHeld = recorder.poll(Set.of(InteractKeys.VK_MENU)::contains);
        recorder.poll(Set.of(InteractKeys.VK_MENU, VK_Q)::contains);
        String shownWhileHeld = recorder.heldSoFar().orElseThrow();
        Optional<InteractKeys> recorded = recorder.poll(NONE::contains);

        assertAll(
                () -> assertEquals(Optional.empty(), whileHeld),
                () -> assertEquals("Alt+Q", shownWhileHeld),
                () -> assertEquals(Optional.of(new InteractKeys(false, false, true, VK_Q)), recorded),
                () -> assertFalse(recorder.isRecording()));
    }

    @Test
    void aKeyWithoutAModifier_recordsNothing_andRecordingGoesOn() {
        recorder.start();

        recorder.poll(Set.of(VK_Q)::contains);
        Optional<InteractKeys> recorded = recorder.poll(NONE::contains);

        assertAll(
                () -> assertEquals(Optional.empty(), recorded),
                () -> assertTrue(recorder.isRecording()),
                () -> assertEquals(Optional.empty(), recorder.heldSoFar()));
    }

    @Test
    void aCancelledRecording_recordsNothing() {
        recorder.start();
        recorder.poll(Set.of(InteractKeys.VK_CONTROL)::contains);

        recorder.cancel();

        assertAll(
                () -> assertEquals(Optional.empty(), recorder.poll(NONE::contains)),
                () -> assertFalse(recorder.isRecording()));
    }
}
