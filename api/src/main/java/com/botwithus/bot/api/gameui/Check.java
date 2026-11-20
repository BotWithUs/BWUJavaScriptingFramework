package com.botwithus.bot.api.gameui;

import java.util.function.Consumer;

/**
 * A check box. The user ticking it raises {@link UiHook#ON_BUTTON_PRESSED}, whose
 * {@code current} is the new state; a script's own {@code checked} set raises nothing.
 */
public final class Check extends InteractiveComponent {

    Check(UiNode node) {
        super(node);
    }

    /** Applies {@code props}, all or nothing. */
    public void set(CheckProps props) {
        node().set(props);
    }

    /** The check's own keys, read back through the client ({@code ui.get}). */
    public UiWidgetState.CheckState state() {
        return switch (node().widget()) {
            case UiWidgetState.CheckState s -> s;
            default -> throw new IllegalStateException("not CheckState keys: " + this);
        };
    }

    /** Whether it is ticked, read back through the client. */
    public boolean isChecked() {
        return state().isChecked();
    }

    /** Listens for {@link UiHook#ON_BUTTON_PRESSED}. */
    public UiSubscription onPressed(Consumer<? super UiHookEvent.ButtonPressed> listener) {
        return onButtonPressed(listener);
    }
}
