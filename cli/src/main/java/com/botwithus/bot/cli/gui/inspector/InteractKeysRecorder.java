package com.botwithus.bot.cli.gui.inspector;

import java.util.Optional;
import java.util.function.IntPredicate;

/**
 * Records an {@link InteractKeys} combination from the keys the user presses:
 * everything held together while recording counts, and the combination is
 * done once every key is let go. A press with no Ctrl, Shift or Alt in it
 * records nothing and recording goes on. Render thread only.
 */
final class InteractKeysRecorder {

    private boolean isRecording;
    private boolean isCtrl;
    private boolean isShift;
    private boolean isAlt;
    private int extraKey = InteractKeys.NO_KEY;

    void start() {
        isRecording = true;
        clearHeld();
    }

    void cancel() {
        isRecording = false;
        clearHeld();
    }

    boolean isRecording() {
        return isRecording;
    }

    /** What is held so far, as it would be written, or empty before the first modifier. */
    Optional<String> heldSoFar() {
        return combination().map(InteractKeys::label);
    }

    /**
     * Called once a frame while recording, with which virtual keys are down
     * now. Returns the combination when the user has let every key go after
     * holding one with a modifier in it; recording then stops.
     */
    Optional<InteractKeys> poll(IntPredicate isDown) {
        if (!isRecording) {
            return Optional.empty();
        }
        boolean isCtrlDown = isDown.test(InteractKeys.VK_CONTROL);
        boolean isShiftDown = isDown.test(InteractKeys.VK_SHIFT);
        boolean isAltDown = isDown.test(InteractKeys.VK_MENU);
        isCtrl |= isCtrlDown;
        isShift |= isShiftDown;
        isAlt |= isAltDown;
        boolean isAnyDown = isCtrlDown || isShiftDown || isAltDown;
        for (int key : InteractKeys.EXTRA_KEYS) {
            if (isDown.test(key)) {
                extraKey = key;
                isAnyDown = true;
            }
        }
        if (isAnyDown) {
            return Optional.empty();
        }
        Optional<InteractKeys> recorded = combination();
        clearHeld();
        if (recorded.isPresent()) {
            isRecording = false;
        }
        return recorded;
    }

    private Optional<InteractKeys> combination() {
        if (!isCtrl && !isShift && !isAlt) {
            return Optional.empty();
        }
        return Optional.of(new InteractKeys(isCtrl, isShift, isAlt, extraKey));
    }

    private void clearHeld() {
        isCtrl = false;
        isShift = false;
        isAlt = false;
        extraKey = InteractKeys.NO_KEY;
    }
}
