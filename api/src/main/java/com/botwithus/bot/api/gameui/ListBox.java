package com.botwithus.bot.api.gameui;

import java.util.List;
import java.util.function.Consumer;

/**
 * A list of entries, the client's {@code list} type; single or multiple select by its
 * {@code selection_limit}. The user's selections raise {@link UiHook#ON_SELECTION_CHANGED},
 * one per entry that changed; a script's own {@code selected} set raises none.
 */
public final class ListBox extends InteractiveComponent {

    ListBox(UiNode node) {
        super(node);
    }

    /** Applies {@code props}, all or nothing. */
    public void set(ListBoxProps props) {
        node().set(props);
    }

    /** The listBox's own keys, read back through the client ({@code ui.get}). */
    public UiWidgetState.ListBoxState state() {
        return switch (node().widget()) {
            case UiWidgetState.ListBoxState s -> s;
            default -> throw new IllegalStateException("not ListBoxState keys: " + this);
        };
    }

    /** The selected entries' ids, read back through the client. */
    public List<Integer> selected() {
        return state().selected();
    }

    /** Listens for {@link UiHook#ON_SELECTION_CHANGED}, decoded as an entry's. */
    public UiSubscription onSelectionChanged(Consumer<? super UiHookEvent.SelectionChanged> listener) {
        return onEntrySelection(listener);
    }
}
