package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.settings.AlertSettingKeys;
import com.botwithus.bot.core.alerts.AlertService;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * One thing in a settings section: a setting's row, or one of the few rows that
 * are not a single setting. Sealed so the page draws each with an exhaustive
 * {@code switch}, and so "Find a setting" can narrow the tables and the
 * Integrations event grid row by row.
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

    /**
     * An Integrations service: its switch and status, and, while it is on, its
     * fields, setup hint and Send test.
     *
     * @param description   one line under the name
     * @param enabledKey    the {@code alerts.<service>.enabled} setting the switch edits
     * @param fields        what the service needs, in display order; drawn only while it is on
     * @param hint          where to find what the fields ask for
     * @param result        how the last send went; empty before the first and while a test is on its way
     * @param canSendTest   {@code false} while a test is on its way
     */
    record ServiceCard(AlertService service, String description, String enabledKey, boolean isEnabled,
                       StatusChip chip, List<CardField> fields, String hint, Optional<ResultLine> result,
                       boolean canSendTest) implements SettingsItem {

        public ServiceCard {
            Objects.requireNonNull(service, "service");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(enabledKey, "enabledKey");
            Objects.requireNonNull(chip, "chip");
            fields = List.copyOf(fields);
            Objects.requireNonNull(hint, "hint");
            Objects.requireNonNull(result, "result");
        }

        @Override
        public String findText() {
            StringBuilder text = new StringBuilder("integrations alerts notifications ")
                    .append(service.label()).append(' ').append(description).append(' ')
                    .append(enabledKey).append(' ').append(hint);
            for (CardField field : fields) {
                text.append(' ').append(field.findText());
            }
            return text.toString().toLowerCase(Locale.ROOT);
        }
    }

    /** Which alert kinds go to which service: rows are kinds, columns are services. */
    record EventGrid(List<GridColumn> columns, List<GridRow> rows) implements SettingsItem {

        public EventGrid {
            columns = List.copyOf(columns);
            rows = List.copyOf(rows);
        }

        @Override
        public String findText() {
            StringBuilder text = new StringBuilder("integrations events which alerts go where send grid");
            for (GridColumn column : columns) {
                text.append(' ').append(column.label());
            }
            return text.toString().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * The quiet-hours switch and its two times, one row.
     *
     * @param from when they start, {@code HH:mm}
     * @param to   when they end, {@code HH:mm}
     */
    record QuietHours(String label, String description, boolean isOn, String from, String to)
            implements SettingsItem {

        @Override
        public String findText() {
            return String.join(" ", label, description, AlertSettingKeys.QUIET_ENABLED.name(),
                    AlertSettingKeys.QUIET_FROM.name(), AlertSettingKeys.QUIET_TO.name()).toLowerCase(Locale.ROOT);
        }
    }

    /**
     * A line the section needs the user to read, such as secrets being kept only
     * until the host closes.
     *
     * @param isWarning draws it in the warning colour rather than as plain text
     */
    record Notice(String text, boolean isWarning) implements SettingsItem {

        @Override
        public String findText() {
            return ("integrations " + text).toLowerCase(Locale.ROOT);
        }
    }
}
