package com.botwithus.bot.api.gameui;

import java.util.function.Consumer;

/**
 * A button. Listen for presses with {@link #onPressed}; a press is the user's, since
 * nothing a script sets raises one.
 */
public final class Button extends InteractiveComponent {

    Button(UiNode node) {
        super(node);
    }

    /** Applies {@code props}, all or nothing. */
    public void set(ButtonProps props) {
        node().set(props);
    }

    /** The button's own keys, read back through the client ({@code ui.get}). */
    public UiWidgetState.ButtonState state() {
        return switch (node().widget()) {
            case UiWidgetState.ButtonState s -> s;
            default -> throw new IllegalStateException("not ButtonState keys: " + this);
        };
    }

    /** Listens for {@link UiHook#ON_BUTTON_PRESSED}. */
    public UiSubscription onPressed(Consumer<? super UiHookEvent.ButtonPressed> listener) {
        return onButtonPressed(listener);
    }
}
