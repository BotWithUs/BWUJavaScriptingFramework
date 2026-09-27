package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.settings.NotificationKind;
import com.botwithus.bot.cli.settings.SettingKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Which settings the page shows as rows, in which section, in which unit. A
 * setting that is not placed here still appears in the All config keys table,
 * so a key added to {@link SettingKeys} is editable on the page from day one.
 */
public final class SettingsLayout {

    private static final List<Placement> PLACEMENTS = build();

    private SettingsLayout() {
    }

    /** Every placed row, in page order. */
    public static List<Placement> placements() {
        return PLACEMENTS;
    }

    /** The rows of {@code section}, in page order. */
    public static List<Placement> in(SettingsSection section) {
        return placements().stream().filter(p -> p.section() == section).toList();
    }

    /** Where the setting called {@code name} is shown as a row, if it is. */
    public static Optional<Placement> find(String name) {
        return placements().stream().filter(p -> p.key().name().equals(name)).findFirst();
    }

    private static List<Placement> build() {
        List<Placement> rows = new ArrayList<>();
        addConnecting(rows);
        addReconnecting(rows);
        addScripts(rows);
        for (NotificationKind kind : NotificationKind.values()) {
            rows.add(Placement.of(SettingsSection.NOTIFICATIONS, SettingKeys.notifyEnabled(kind)));
        }
        rows.add(Placement.of(SettingsSection.NOTIFICATIONS, SettingKeys.NOTIFY_DURATION_S, DisplayUnit.SECONDS));
        rows.add(Placement.of(SettingsSection.INTERFACE, SettingKeys.START_MODE));
        rows.add(Placement.of(SettingsSection.INTERFACE, SettingKeys.TEXT_SIZE));
        rows.add(Placement.of(SettingsSection.INTERFACE, SettingKeys.REDUCE_MOTION));
        rows.add(Placement.of(SettingsSection.INTERFACE, SettingKeys.NATIVE_FRAME));
        rows.add(Placement.of(SettingsSection.DIAGNOSTICS, SettingKeys.COLLECT_RPC_TIMING));
        rows.add(Placement.of(SettingsSection.DIAGNOSTICS, SettingKeys.COLLECT_LOOP_TIMING));
        return List.copyOf(rows);
    }

    private static void addConnecting(List<Placement> rows) {
        rows.add(Placement.of(SettingsSection.CONNECTING, SettingKeys.AUTO_CONNECT));
        rows.add(Placement.of(SettingsSection.CONNECTING, SettingKeys.PIPE_PREFIX));
        rows.add(new Placement(SettingsSection.CONNECTING, SettingKeys.SCAN_INTERVAL_MS,
                DisplayUnit.SECONDS_FROM_MS, "How often to look for new pipes while auto-connect is on."));
        rows.add(new Placement(SettingsSection.CONNECTING, SettingKeys.RPC_TIMEOUT_MS,
                DisplayUnit.MILLISECONDS, "Give up on a single call to the game after this long."));
    }

    private static void addReconnecting(List<Placement> rows) {
        rows.add(Placement.of(SettingsSection.RECONNECTING, SettingKeys.RECONNECT_MAX_ATTEMPTS, DisplayUnit.TRIES));
        rows.add(new Placement(SettingsSection.RECONNECTING, SettingKeys.RECONNECT_INITIAL_DELAY_MS,
                DisplayUnit.MILLISECONDS, "Wait before the first retry."));
        rows.add(Placement.of(SettingsSection.RECONNECTING, SettingKeys.RECONNECT_BACKOFF, DisplayUnit.TIMES));
        rows.add(new Placement(SettingsSection.RECONNECTING, SettingKeys.RECONNECT_MAX_DELAY_MS,
                DisplayUnit.MILLISECONDS, "Never wait longer than this between tries."));
    }

    private static void addScripts(List<Placement> rows) {
        rows.add(Placement.of(SettingsSection.SCRIPTS, SettingKeys.AUTO_RELOAD));
        rows.add(Placement.of(SettingsSection.SCRIPTS, SettingKeys.RESTART_AFTER_RELOAD));
        rows.add(new Placement(SettingsSection.SCRIPTS, SettingKeys.STALL_AFTER_MS, DisplayUnit.SECONDS_FROM_MS,
                "Flag a script when one loop runs longer than this."));
    }
}
