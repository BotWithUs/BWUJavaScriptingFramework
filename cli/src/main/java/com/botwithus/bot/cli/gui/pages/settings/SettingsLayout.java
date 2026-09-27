package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.settings.AlertSettingKeys;
import com.botwithus.bot.cli.settings.NotificationKind;
import com.botwithus.bot.cli.settings.SettingKeys;

import java.time.Duration;
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
        addIntegrations(rows);
        rows.add(Placement.of(SettingsSection.INTERFACE, SettingKeys.START_MODE));
        rows.add(Placement.of(SettingsSection.INTERFACE, SettingKeys.TEXT_SIZE));
        rows.add(Placement.of(SettingsSection.INTERFACE, SettingKeys.REDUCE_MOTION));
        rows.add(Placement.of(SettingsSection.INTERFACE, SettingKeys.NATIVE_FRAME));
        rows.add(Placement.of(SettingsSection.DIAGNOSTICS, SettingKeys.COLLECT_RPC_TIMING));
        rows.add(Placement.of(SettingsSection.DIAGNOSTICS, SettingKeys.COLLECT_LOOP_TIMING));
        return List.copyOf(rows);
    }

    /**
     * The Integrations section's plain rows. The service cards, the event grid and
     * the quiet-hours row are built by {@link IntegrationsSheet}, which places these
     * three around them and words the mode row for the mode it is in.
     */
    private static void addIntegrations(List<Placement> rows) {
        rows.add(new Placement(SettingsSection.INTEGRATIONS, AlertSettingKeys.BURST_SECONDS, DisplayUnit.SECONDS,
                "If several clients drop at once, send one message listing them instead of one each. "
                        + "0 sends each at once."));
        rows.add(new Placement(SettingsSection.INTEGRATIONS, AlertSettingKeys.QUIET_MODE, DisplayUnit.NONE,
                "What quiet hours do with the alerts they hold back: send them when they end, or drop them."));
        rows.add(new Placement(SettingsSection.INTEGRATIONS, AlertSettingKeys.SUMMARY_AT, DisplayUnit.NONE,
                "When to send the daily summary (24-hour, local time), to the services ticked for it above."));
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

    /** The stall row is in seconds, which would show the default as 600; say it in minutes. */
    private static long stallDefaultMinutes() {
        return Duration.ofMillis(SettingKeys.STALL_AFTER_MS.defaultValue()).toMinutes();
    }

    private static void addScripts(List<Placement> rows) {
        rows.add(Placement.of(SettingsSection.SCRIPTS, SettingKeys.AUTO_RELOAD));
        rows.add(Placement.of(SettingsSection.SCRIPTS, SettingKeys.RESTART_AFTER_RELOAD));
        rows.add(new Placement(SettingsSection.SCRIPTS, SettingKeys.STALL_AFTER_MS, DisplayUnit.SECONDS_FROM_MS,
                "Flag a script when one loop runs longer than this. The default, " + stallDefaultMinutes()
                        + " minutes, leaves room for scripts that wait a long time on purpose."));
    }
}
