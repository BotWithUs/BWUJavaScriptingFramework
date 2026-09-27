package com.botwithus.bot.cli.gui.pages.settings;

import java.util.List;

/**
 * The one control on the right of a settings row, picked from the setting's
 * type by {@link SettingsSheet#controlFor}. Values are in the row's display
 * form; an edit sends back text in the same form.
 */
public sealed interface RowControl {

    /** An on/off switch; an edit sends {@code "true"} or {@code "false"}. */
    record Switch(boolean isOn) implements RowControl {}

    /**
     * A number box with its unit drawn after the value.
     *
     * @param text what the box shows, already in {@code unit}
     * @param unit the suffix, or empty for none
     */
    record NumberBox(String text, String unit) implements RowControl {}

    /** A free-text box. */
    record TextField(String text) implements RowControl {}

    /** A few choices side by side, one pressed. */
    record Segments(List<Option> options, int selected) implements RowControl {

        public Segments {
            options = List.copyOf(options);
        }
    }

    /** More choices than fit side by side, in a drop-down. */
    record Dropdown(List<Option> options, int selected) implements RowControl {

        public Dropdown {
            options = List.copyOf(options);
        }
    }

    /**
     * One choice.
     *
     * @param value what an edit sends when it is picked
     * @param label what the control shows
     */
    record Option(String value, String label) {}
}
