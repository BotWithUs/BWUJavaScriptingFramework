package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.alerts.Integrations;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.ReconnectPolicySettings;
import com.botwithus.bot.cli.settings.SaveStatus;
import com.botwithus.bot.cli.settings.SettingKey;
import com.botwithus.bot.cli.settings.SettingType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds the {@link SettingsView} from the live settings. Both the real model
 * and the preview fixture go through here, so the rows, controls and tables the
 * preview captures are the ones the host shows.
 */
public final class SettingsSheet {

    /** A choice with more options than this is a drop-down rather than side-by-side buttons. */
    static final int MAX_SEGMENTS = 3;

    private static final String NOT_SHOWN_ABOVE = "—";
    private static final String UNKNOWN_KEY = "not a known setting · read-only";

    /**
     * What the sheet needs besides the settings themselves.
     *
     * @param accounts       the saved account profiles
     * @param dataFolder     the data folder, as the About row shows it
     * @param scriptsFolder  the scripts folder, as the Scripts row shows it
     * @param configFile     the settings file, as the header shows it
     * @param windowsPercent the monitor's display scaling, for the text-size choice
     * @param note           what the last button did
     * @param integrations   the alert services, once the alerts have started
     */
    public record Inputs(List<AccountRow> accounts, String dataFolder, String scriptsFolder, String configFile,
                         int windowsPercent, Optional<ActionNote> note, Optional<Integrations> integrations) {

        public Inputs {
            accounts = List.copyOf(accounts);
            Objects.requireNonNull(integrations, "integrations");
        }

        /** Inputs for a host whose alerts have not started. */
        public Inputs(List<AccountRow> accounts, String dataFolder, String scriptsFolder, String configFile,
                      int windowsPercent, Optional<ActionNote> note) {
            this(accounts, dataFolder, scriptsFolder, configFile, windowsPercent, note, Optional.empty());
        }
    }

    private SettingsSheet() {
    }

    /** The whole page for {@code settings} as they stand, with {@code status} in the header. */
    public static SettingsView build(HostSettings settings, SaveStatus status, Inputs inputs) {
        List<SectionView> sections = new ArrayList<>();
        for (SettingsSection section : SettingsSection.values()) {
            sections.add(new SectionView(section, items(section, settings, inputs)));
        }
        return new SettingsView(SaveLine.of(status), inputs.configFile(), sections, inputs.note());
    }

    private static List<SettingsItem> items(SettingsSection section, HostSettings settings, Inputs in) {
        if (section == SettingsSection.INTEGRATIONS) {
            // Its plain rows sit among its cards, grid and quiet hours, so it places them itself.
            return IntegrationsSheet.items(settings, in.integrations(),
                    placement -> keyRow(placement, settings, in.windowsPercent()));
        }
        List<SettingsItem> items = new ArrayList<>(keyRows(section, settings, in.windowsPercent()));
        switch (section) {
            case RECONNECTING -> items.add(new SettingsItem.WaitPreview(
                    ReconnectPreview.of(ReconnectPolicySettings.read(settings))));
            case ACCOUNTS -> items.add(new SettingsItem.Accounts(in.accounts()));
            case SCRIPTS -> items.add(new SettingsItem.PlaceRow(SettingsPlace.SCRIPTS_FOLDER, "Scripts folder",
                    "Where script JARs are loaded from.", in.scriptsFolder()));
            case DIAGNOSTICS -> items.add(new SettingsItem.ActionRow(SettingsAction.RESET_METRICS,
                    "Reset all metrics", "Clears RPC and loop numbers on every client."));
            case ALL_KEYS -> items.add(new SettingsItem.RawKeys(rawKeys(settings)));
            case ABOUT -> {
                items.add(new SettingsItem.PlaceRow(SettingsPlace.DATA_FOLDER, "Data folder",
                        "Config, profiles, groups and logs live here.", in.dataFolder()));
                items.add(new SettingsItem.ActionRow(SettingsAction.EXPORT_SETTINGS, "Export settings",
                        "Save config, profiles and groups to one file to move them to another PC."));
            }
            case CONNECTING, NOTIFICATIONS, INTEGRATIONS, INTERFACE -> { }
        }
        return items;
    }

    private static List<SettingsItem> keyRows(SettingsSection section, HostSettings settings, int windowsPercent) {
        List<SettingsItem> rows = new ArrayList<>();
        for (Placement placement : SettingsLayout.in(section)) {
            rows.add(keyRow(placement, settings, windowsPercent));
        }
        return rows;
    }

    private static SettingsItem keyRow(Placement placement, HostSettings settings, int windowsPercent) {
        SettingKey<?> key = placement.key();
        return new SettingsItem.KeyRow(key.name(), key.label(), placement.description(),
                controlFor(key, settings.text(key), placement.unit(), windowsPercent), settings.isExplicit(key));
    }

    /**
     * The control for a setting of {@code key}'s type, showing {@code text} (its
     * stored form) in {@code unit}.
     */
    public static RowControl controlFor(SettingKey<?> key, String text, DisplayUnit unit, int windowsPercent) {
        return switch (key.type()) {
            case SettingType.Flag _ -> new RowControl.Switch(Boolean.parseBoolean(text));
            case SettingType.WholeNumber _, SettingType.Decimal _ ->
                    new RowControl.NumberBox(unit.shown(text), unit.suffix());
            case SettingType.Text _ -> new RowControl.TextField(text);
            case SettingType.Choice<?> choice -> choiceControl(key.name(), choice, text, windowsPercent);
        };
    }

    private static RowControl choiceControl(String keyName, SettingType.Choice<?> choice, String text,
                                            int windowsPercent) {
        List<RowControl.Option> options = new ArrayList<>();
        int selected = -1;
        for (Enum<?> option : choice.options()) {
            if (option.name().equalsIgnoreCase(text)) {
                selected = options.size();
            }
            options.add(new RowControl.Option(option.name(),
                    ChoiceLabels.label(keyName, option.name(), windowsPercent)));
        }
        return options.size() <= MAX_SEGMENTS
                ? new RowControl.Segments(options, selected)
                : new RowControl.Dropdown(options, selected);
    }

    private static List<RawKeyRow> rawKeys(HostSettings settings) {
        List<RawKeyRow> rows = new ArrayList<>();
        for (SettingKey<?> key : settings.keys()) {
            String shownAs = SettingsLayout.find(key.name()).map(p -> p.key().label())
                    .or(() -> IntegrationsSheet.shownAs(key.name())).orElse(NOT_SHOWN_ABOVE);
            rows.add(new RawKeyRow(key.name(), settings.text(key), shownAs, true, settings.isExplicit(key)));
        }
        for (Map.Entry<String, String> entry : settings.unknownEntries().entrySet()) {
            rows.add(new RawKeyRow(entry.getKey(), entry.getValue(), UNKNOWN_KEY, false, true));
        }
        return rows;
    }
}
