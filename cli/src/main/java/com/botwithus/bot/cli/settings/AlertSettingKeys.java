package com.botwithus.bot.cli.settings;

import com.botwithus.bot.core.alerts.AlertKind;
import com.botwithus.bot.core.alerts.AlertService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The {@code alerts.*} settings: which services are on, what each one is sent, and
 * the ntfy server and topic. Every value here is non-secret; webhook URLs and the
 * ntfy token live in the credential store, never in {@code config.properties}.
 *
 * <p>Everything is off by default: the host makes no outbound request until the
 * user turns a service on.</p>
 */
public final class AlertSettingKeys {

    /** A clock time, {@code HH:mm}, 24-hour. */
    public static final SettingType<String> TIME_OF_DAY = new SettingType.Text(
            Pattern.compile("([01][0-9]|2[0-3]):[0-5][0-9]"), "a time of day, HH:mm (24-hour)");

    private static final SettingType<Boolean> FLAG = new SettingType.Flag();
    private static final long MAX_BURST_SECONDS = 600L;
    private static final long DEFAULT_BURST_SECONDS = 30L;
    private static final int MAX_URL_LENGTH = 200;

    /** Kinds each service is sent until the user changes its column of the grid. */
    private static final Set<AlertKind> ROUTED_BY_DEFAULT =
            EnumSet.of(AlertKind.CLIENT_LOST, AlertKind.CLIENT_CLOSED, AlertKind.SCRIPT_CRASH);

    private static final Map<AlertService, SettingKey<Boolean>> ENABLED = enabledKeys();
    private static final Map<AlertService, Map<AlertKind, SettingKey<Boolean>>> ROUTES = routeKeys();

    public static final SettingKey<String> NTFY_SERVER = new SettingKey<>(
            "alerts.ntfy.server", "ntfy server",
            "The ntfy server to publish to: ntfy.sh or your own.",
            new SettingType.Text(Pattern.compile("https?://\\S{1," + MAX_URL_LENGTH + "}"),
                    "an http:// or https:// URL"),
            "https://ntfy.sh");

    public static final SettingKey<String> NTFY_TOPIC = new SettingKey<>(
            "alerts.ntfy.topic", "ntfy topic",
            "The topic to publish to. Anyone who knows it can read it, so keep it hard to guess.",
            new SettingType.Text(Pattern.compile("[A-Za-z0-9_-]{0,64}"),
                    "up to 64 letters, digits, '_' or '-'"),
            "");

    public static final SettingKey<Boolean> DISCORD_MENTION_HERE = new SettingKey<>(
            "alerts.discord.mentionHere", "Mention @here on errors",
            "Start Discord messages about a problem with @here.",
            FLAG, Boolean.FALSE);

    public static final SettingKey<Long> BURST_SECONDS = new SettingKey<>(
            "alerts.burstS", "Group bursts",
            "Send alerts that arrive within this many seconds as one message. 0 sends each at once.",
            new SettingType.WholeNumber(0L, MAX_BURST_SECONDS), DEFAULT_BURST_SECONDS);

    public static final SettingKey<Boolean> QUIET_ENABLED = new SettingKey<>(
            "alerts.quiet.enabled", "Quiet hours",
            "Hold back every alert except crashes between the quiet-hours times.",
            FLAG, Boolean.FALSE);

    public static final SettingKey<String> QUIET_FROM = new SettingKey<>(
            "alerts.quiet.from", "Quiet from", "When quiet hours start (local time).",
            TIME_OF_DAY, "00:00");

    public static final SettingKey<String> QUIET_TO = new SettingKey<>(
            "alerts.quiet.to", "Quiet until", "When quiet hours end (local time).",
            TIME_OF_DAY, "08:00");

    public static final SettingKey<String> SUMMARY_AT = new SettingKey<>(
            "alerts.summary.at", "Daily summary at",
            "When to send the daily summary (local time), to the services it is ticked for.",
            TIME_OF_DAY, "22:00");

    /** Every alert key, in settings-page order. Immutable. */
    public static final List<SettingKey<?>> ALL = catalogue();

    private AlertSettingKeys() {
    }

    /** {@code alerts.<service>.enabled}: whether the service gets anything at all. */
    public static SettingKey<Boolean> enabled(AlertService service) {
        return ENABLED.get(service);
    }

    /** {@code alerts.<service>.send.<kind>}: one cell of the event grid. */
    public static SettingKey<Boolean> route(AlertService service, AlertKind kind) {
        return ROUTES.get(service).get(kind);
    }

    private static Map<AlertService, SettingKey<Boolean>> enabledKeys() {
        Map<AlertService, SettingKey<Boolean>> keys = new EnumMap<>(AlertService.class);
        for (AlertService service : AlertService.values()) {
            keys.put(service, new SettingKey<>("alerts." + service.id() + ".enabled",
                    "Send alerts to " + service.label(),
                    "Turn " + service.label() + " alerts on or off.", FLAG, Boolean.FALSE));
        }
        return Collections.unmodifiableMap(keys);
    }

    private static Map<AlertService, Map<AlertKind, SettingKey<Boolean>>> routeKeys() {
        Map<AlertService, Map<AlertKind, SettingKey<Boolean>>> keys = new EnumMap<>(AlertService.class);
        for (AlertService service : AlertService.values()) {
            Map<AlertKind, SettingKey<Boolean>> column = new EnumMap<>(AlertKind.class);
            for (AlertKind kind : AlertKind.values()) {
                column.put(kind, new SettingKey<>("alerts." + service.id() + ".send." + kind.id(),
                        service.label() + ": " + kind.label(),
                        "Send \"" + kind.label() + "\" alerts to " + service.label() + ".",
                        FLAG, ROUTED_BY_DEFAULT.contains(kind)));
            }
            keys.put(service, Collections.unmodifiableMap(column));
        }
        return Collections.unmodifiableMap(keys);
    }

    private static List<SettingKey<?>> catalogue() {
        List<SettingKey<?>> keys = new ArrayList<>();
        for (AlertService service : AlertService.values()) {
            keys.add(enabled(service));
        }
        keys.addAll(List.of(NTFY_SERVER, NTFY_TOPIC, DISCORD_MENTION_HERE));
        for (AlertKind kind : AlertKind.values()) {
            for (AlertService service : AlertService.values()) {
                keys.add(route(service, kind));
            }
        }
        keys.addAll(List.of(BURST_SECONDS, QUIET_ENABLED, QUIET_FROM, QUIET_TO, SUMMARY_AT));
        return List.copyOf(keys);
    }
}
