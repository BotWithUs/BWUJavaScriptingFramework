package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.alerts.SecretChange;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.PreviewSettings;
import com.botwithus.bot.cli.settings.SaveStatus;
import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.secrets.Secret;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DEV ONLY. The Settings page over real settings kept in memory, eight sample
 * accounts, a save status the scenario picks, and the real Integrations back end
 * over {@link FixtureIntegrations}. Edits go through the real
 * {@link SettingEditor}, so a refused value shows the message the host would.
 */
public final class FixtureSettingsModel implements SettingsModel {

    private static final int WINDOWS_PERCENT = 150;
    private static final int CLIENTS = 6;
    private static final int EXPORTED_FILES = 7;

    private final HostSettings settings = PreviewSettings.inMemory(Map.of(
            "autoReload", "true",
            "scripts.stallAfterMs", "30000",
            "theme.accent", "#4ade80",
            "alerts.ntfy.topic", "bwu-alerts-preview"));
    private final SettingEditor editor = new SettingEditor(settings);
    private final FixtureIntegrations integrations = new FixtureIntegrations(settings);
    private final List<AccountRow> accounts = new ArrayList<>(List.of(
            account("3f9a1c2e", "Oakheart", true, "Woodcutting"),
            account("b71d09e4", "Fernmoss", true, "Divination"),
            account("e4410b7a", "Hollowmere", true, "Woodcutting"),
            account("0a6d2f58", "Brackenridge", true, "Divination"),
            account("90ce3f15", "Tamsin Vale", true, "Cook's Assistant"),
            account("81c5e0b2", "Kestrel Moor", true, "Walk to Flag", "Location Probe"),
            account("5c20aa91", "Quillon", false),
            account("2d7b4e91", "Mirelock", false, "Woodcutting")));
    private SaveStatus status = new SaveStatus.Saved(Instant.now());
    private Optional<ActionNote> note = Optional.empty();

    /** The alert services behind the Integrations section, for a scenario to set up. */
    public FixtureIntegrations integrations() {
        return integrations;
    }

    /** What the header reports from now on. */
    public void showStatus(SaveStatus shown) {
        this.status = shown;
    }

    @Override
    public SettingsView view() {
        SettingsSheet.Inputs inputs = new SettingsSheet.Inputs(accounts, "~/.botwithus/", "scripts/",
                "~/.botwithus/config.properties", WINDOWS_PERCENT, note, Optional.of(integrations.live()));
        return SettingsSheet.build(settings, status, inputs);
    }

    @Override
    public SaveLine save() {
        return SaveLine.of(status);
    }

    @Override
    public EditResult edit(String name, String shownText) {
        return editor.editRow(name, shownText);
    }

    @Override
    public EditResult editRaw(String name, String text) {
        return editor.editRaw(name, text);
    }

    @Override
    public void setAutoStart(String accountUuid, boolean isOn) {
        accounts.replaceAll(a -> a.uuid().equals(accountUuid) ? new AccountRow(a.uuid(), a.name(), a.scripts(), isOn)
                : a);
    }

    @Override
    public void forgetScripts(String accountUuid) {
        accounts.replaceAll(a -> a.uuid().equals(accountUuid)
                ? new AccountRow(a.uuid(), a.name(), List.of(), a.isAutoStart()) : a);
    }

    @Override
    public void open(SettingsPlace place) {
        note = Optional.of(ActionNote.done("Opened " + place + " (fixture)."));
    }

    @Override
    public void run(SettingsAction action) {
        note = Optional.of(switch (action) {
            case RESET_METRICS -> ActionNote.done("Metrics reset on " + CLIENTS + " clients.");
            case EXPORT_SETTINGS -> ActionNote.done("Saved " + EXPORTED_FILES
                    + " files to ~/Downloads/botwithus-settings-20260926-140500.zip.");
        });
    }

    @Override
    public Optional<Secret> readSecret(AlertService service) {
        return integrations.live().readSecret(service);
    }

    @Override
    public SecretChange saveSecret(AlertService service, String text) {
        return integrations.live().saveSecret(service, text);
    }

    @Override
    public void sendTest(AlertService service) {
        integrations.live().sendTest(service);
    }

    private static AccountRow account(String shortUuid, String name, boolean isAutoStart, String... scripts) {
        return new AccountRow(shortUuid + "-7d41-4b8e-9a51-0c2f6b1d8e30", name, List.of(scripts), isAutoStart);
    }
}
