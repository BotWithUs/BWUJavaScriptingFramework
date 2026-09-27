package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.cli.settings.AlertSettingKeys;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SettingKey;
import com.botwithus.bot.cli.settings.Subscription;
import com.botwithus.bot.core.alerts.AlertKind;
import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.alerts.QuietHours;

import java.time.Duration;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code alerts.*} settings, read live and typed. Every read goes to
 * {@link HostSettings}, so a change on the Settings page applies to the next alert.
 */
public final class AlertSettings {

    private final HostSettings settings;

    public AlertSettings(HostSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** Whether {@code service} is switched on. */
    public boolean isEnabled(AlertService service) {
        return settings.get(AlertSettingKeys.enabled(service));
    }

    /** Whether the event grid sends {@code kind} to {@code service}, ignoring the service switch. */
    public boolean isRouted(AlertService service, AlertKind kind) {
        return settings.get(AlertSettingKeys.route(service, kind));
    }

    /** The services an alert of {@code kind} goes to: switched on, and ticked in the grid. */
    public Set<AlertService> targets(AlertKind kind) {
        Set<AlertService> targets = EnumSet.noneOf(AlertService.class);
        for (AlertService service : AlertService.values()) {
            if (isEnabled(service) && isRouted(service, kind)) {
                targets.add(service);
            }
        }
        return targets;
    }

    /** How long a burst stays open; zero means every alert goes out at once. */
    public Duration burstWindow() {
        return Duration.ofSeconds(settings.get(AlertSettingKeys.BURST_SECONDS));
    }

    /** The quiet hours, when they are switched on. */
    public Optional<QuietHours> quietHours() {
        if (!settings.get(AlertSettingKeys.QUIET_ENABLED)) {
            return Optional.empty();
        }
        return Optional.of(new QuietHours(time(AlertSettingKeys.QUIET_FROM), time(AlertSettingKeys.QUIET_TO)));
    }

    /** When the daily summary is due, local time. */
    public LocalTime summaryAt() {
        return time(AlertSettingKeys.SUMMARY_AT);
    }

    /** The ntfy server, as typed. */
    public String ntfyServer() {
        return settings.get(AlertSettingKeys.NTFY_SERVER);
    }

    /** The ntfy topic; empty until the user sets one. */
    public String ntfyTopic() {
        return settings.get(AlertSettingKeys.NTFY_TOPIC);
    }

    /** Whether Discord messages about a problem start with {@code @here}. */
    public boolean isMentioningHere() {
        return settings.get(AlertSettingKeys.DISCORD_MENTION_HERE);
    }

    /** Runs {@code listener} whenever the daily summary time changes. */
    public Subscription onSummaryTimeChange(Runnable listener) {
        return settings.onChange(AlertSettingKeys.SUMMARY_AT, value -> listener.run());
    }

    private LocalTime time(SettingKey<String> key) {
        return LocalTime.parse(settings.get(key));
    }
}
