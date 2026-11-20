package com.botwithus.bot.api.gameui;

import java.util.List;

/**
 * The type-specific half of an interactive component's readback ({@code ui.get}),
 * one variant per type that has keys of its own. A grid has none, and neither does
 * any P1 type, so their {@link UiReadback#widget()} is empty. Every component class
 * hands out its own variant through {@code state()}, so a script rarely switches
 * over this itself.
 */
public sealed interface UiWidgetState {

    /**
     * @param label       the button's label
     * @param canToggle   whether a press toggles it
     * @param isToggled   whether it is toggled on
     */
    record ButtonState(String label, boolean canToggle, boolean isToggled) implements UiWidgetState {
        public ButtonState {
            label = label != null ? label : "";
        }
    }

    /** @param isChecked whether the check is ticked */
    record CheckState(boolean isChecked) implements UiWidgetState {
    }

    /**
     * The field's content is not here: read it with {@link InputField#content()}.
     *
     * @param isFocused   whether the field holds the client's input focus; while it
     *                    does, the game's keys go to it (see {@link InputField#blur()})
     * @param filter      what input the field accepts
     * @param maxLength   the longest content it keeps
     * @param fontId      the font the agent copied from a client field; {@code -1}: none found
     * @param spriteId    the background sprite the agent copied; {@code -1}: none found
     * @param isRendering whether the client can draw the field at all. <b>Often
     *                    {@code false} on 951-4</b>: an agent field does not draw, not even
     *                    its text, until a background sprite is known, and in the world
     *                    there is usually no client field to copy one from. Content,
     *                    events and focus work regardless.
     */
    record InputFieldState(boolean isFocused, InputFilter filter, int maxLength, int fontId, int spriteId,
                           boolean isRendering) implements UiWidgetState {
        public InputFieldState {
            if (filter == null) {
                throw new IllegalArgumentException("filter");
            }
        }
    }

    /** The slider's range and position. */
    record SliderState(int min, int max, int value, int step, int ticks) implements UiWidgetState {
    }

    /**
     * @param entries  every entry, in order
     * @param selected the selected entry's id, or {@link ComboFieldProps#NO_SELECTION}
     */
    record ComboFieldState(List<UiEntry> entries, int selected) implements UiWidgetState {
        public ComboFieldState {
            entries = List.copyOf(entries);
        }
    }

    /**
     * @param entries        every entry, in order, with its flags
     * @param selected       the selected entries' ids
     * @param selectionLimit {@code 1} for single select; a limit {@code L > 1} holds at
     *                       most {@code L - 1} entries (measured on 951-4)
     */
    record ListBoxState(List<ListEntry> entries, List<Integer> selected, int selectionLimit)
            implements UiWidgetState {
        public ListBoxState {
            entries = List.copyOf(entries);
            selected = List.copyOf(selected);
        }
    }

    /** @param isVertical whether the panel lays its children out top to bottom */
    record PanelState(boolean isVertical) implements UiWidgetState {
    }
}
