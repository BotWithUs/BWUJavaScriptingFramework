package com.botwithus.bot.cli.gui.pages.settings;

import java.util.List;
import java.util.Locale;

/**
 * One thing in a settings section: a setting's row, or one of the few rows that
 * are not a single setting. Sealed so the page draws each with an exhaustive
 * {@code switch}, and so "Find a setting" can narrow the two tables row by row.
 */
public sealed interface SettingsItem {

    /** Lower-case words "Find a setting" matches against. */
    String findText();

    /**
     * A setting with its control.
     *
     * @param name       the property name, shown in mono after the description
     * @param isExplicit the file sets it; otherwise it is at its default
     */
    record KeyRow(String name, String label, String description, RowControl control, boolean isExplicit)
            implements SettingsItem {

        @Override
        public String findText() {
            return (label + " " + description + " " + name).toLowerCase(Locale.ROOT);
        }
    }

    /** The bar chart of reconnect waits. */
    record WaitPreview(ReconnectPreview preview) implements SettingsItem {

        @Override
        public String findText() {
            return "preview reconnect back off backoff wait retry";
        }
    }

    /** The saved account profiles. */
    record Accounts(List<AccountRow> rows) implements SettingsItem {

        public Accounts {
            rows = List.copyOf(rows);
        }

        @Override
        public String findText() {
            return "accounts auto-start autostart resume profiles uuid";
        }
    }

    /** A folder or file with an Open button. */
    record PlaceRow(SettingsPlace place, String label, String description, String path) implements SettingsItem {

        @Override
        public String findText() {
            return (label + " " + description + " " + path + " folder open").toLowerCase(Locale.ROOT);
        }
    }

    /** A button that does something once. */
    record ActionRow(SettingsAction action, String label, String description) implements SettingsItem {

        @Override
        public String findText() {
            return (label + " " + description).toLowerCase(Locale.ROOT);
        }
    }

    /** Every key in {@code config.properties}, known or not. */
    record RawKeys(List<RawKeyRow> rows) implements SettingsItem {

        public RawKeys {
            rows = List.copyOf(rows);
        }

        @Override
        public String findText() {
            return "all config keys properties raw console";
        }
    }
}
