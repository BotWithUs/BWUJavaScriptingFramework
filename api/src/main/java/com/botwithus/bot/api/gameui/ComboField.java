package com.botwithus.bot.api.gameui;

import java.util.function.Consumer;

/**
 * A drop-down of entries, one selected at most. The user's choice raises
 * {@link UiHook#ON_SELECTION_CHANGED}; a script's own {@code selected} set does not.
 */
public final class ComboField extends InteractiveComponent {

    ComboField(UiNode node) {
        super(node);
    }

    /** Applies {@code props}, all or nothing. */
    public void set(ComboFieldProps props) {
        node().set(props);
    }

    /** The comboField's own keys, read back through the client ({@code ui.get}). */
    public UiWidgetState.ComboFieldState state() {
        return switch (node().widget()) {
            case UiWidgetState.ComboFieldState s -> s;
            default -> throw new IllegalStateException("not ComboFieldState keys: " + this);
        };
    }

    /** The selected entry's id, or {@link ComboFieldProps#NO_SELECTION}. */
    public int selected() {
        return state().selected();
    }

    /** Listens for {@link UiHook#ON_SELECTION_CHANGED}, decoded as an entry's. */
    public UiSubscription onSelectionChanged(Consumer<? super UiHookEvent.SelectionChanged> listener) {
        return onEntrySelection(listener);
    }
}
