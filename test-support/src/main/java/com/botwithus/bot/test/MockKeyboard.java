package com.botwithus.bot.test;

import com.botwithus.bot.api.domain.KeyboardAPI;
import com.botwithus.bot.api.input.FocusMode;
import com.botwithus.bot.api.input.FocusState;
import com.botwithus.bot.api.input.KeyResult;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * The keyboard half of {@link MockGameAPI}: answers the {@link KeyboardAPI}
 * calls the way the agent does, without a client.
 *
 * <p>A key is delivered (and recorded) when the focus mode is
 * {@link FocusMode#AGENT} or the client "really" has focus; otherwise it is
 * refused as {@link KeyResult#NOT_FOCUSED} and nothing is recorded.
 * {@link FocusMode#AGENT} on a mock configured without it is refused softly,
 * as on a client whose focus import was not found: the mode stays as it was
 * and the returned state says {@code available = false}.</p>
 */
final class MockKeyboard {

    private final boolean realFocusOnView;
    private final boolean agentAvailable;
    private final Consumer<String> keySink;
    private final Consumer<FocusMode> focusSink;
    private FocusMode mode;

    MockKeyboard(FocusMode initialMode, boolean realFocusOnView, boolean agentAvailable,
                 Consumer<String> keySink, Consumer<FocusMode> focusSink) {
        this.mode = Objects.requireNonNull(initialMode, "initialMode");
        this.realFocusOnView = realFocusOnView;
        this.agentAvailable = agentAvailable;
        this.keySink = Objects.requireNonNull(keySink, "keySink");
        this.focusSink = Objects.requireNonNull(focusSink, "focusSink");
    }

    synchronized KeyResult sendKey(int virtualKey, int holdMs) {
        checkRange("virtualKey", virtualKey, KeyboardAPI.MAX_VIRTUAL_KEY);
        checkRange("holdMs", holdMs, KeyboardAPI.MAX_HOLD_MS);
        if (mode == FocusMode.REAL && !realFocusOnView) {
            return KeyResult.NOT_FOCUSED;
        }
        keySink.accept(virtualKey + "/" + holdMs);
        return KeyResult.SENT;
    }

    synchronized FocusState setFocusMode(FocusMode requested) {
        Objects.requireNonNull(requested, "mode");
        if (requested == FocusMode.AGENT && !agentAvailable) {
            return focusMode();
        }
        mode = requested;
        focusSink.accept(requested);
        return focusMode();
    }

    synchronized FocusState focusMode() {
        return new FocusState(mode, mode == FocusMode.AGENT, agentAvailable, realFocusOnView);
    }

    private static void checkRange(String name, int value, int max) {
        if (value < 0 || value > max) {
            throw new IllegalArgumentException(name + " must be 0.." + max + ", was " + value);
        }
    }
}
