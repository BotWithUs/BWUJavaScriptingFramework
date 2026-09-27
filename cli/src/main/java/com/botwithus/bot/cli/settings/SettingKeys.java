package com.botwithus.bot.cli.settings;

import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.runtime.ScriptRuntime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Every setting the host knows, with its default, bounds and description.
 *
 * <p>{@link #ALL} is the catalogue {@link HostSettings} validates against, in the
 * order a settings page lists them. A name in {@code config.properties} that is
 * not here is kept verbatim and reported by {@link HostSettings#unknownEntries()}.</p>
 *
 * <p>Several keys are defined ahead of the code that reads them, so the Settings
 * page and {@code config set} already accept them; each key's consumer reads it
 * live through {@link HostSettings#get} or {@link HostSettings#onChange}.</p>
 */
public final class SettingKeys {

    private static final long MS_PER_SECOND = 1_000L;
    private static final long MS_PER_MINUTE = 60_000L;

    private static final String DEFAULT_PIPE_PREFIX = "BotWithUs";
    private static final int MAX_PIPE_PREFIX_LENGTH = 64;
    private static final long DEFAULT_SCAN_INTERVAL_MS = 5 * MS_PER_SECOND;
    private static final long MIN_SCAN_INTERVAL_MS = 500L;
    private static final long MAX_SCAN_INTERVAL_MS = 10 * MS_PER_MINUTE;
    private static final long DEFAULT_RPC_TIMEOUT_MS = RpcClient.DEFAULT_TIMEOUT_MS;
    private static final long MIN_RPC_TIMEOUT_MS = 100L;
    private static final long MAX_RPC_TIMEOUT_MS = 10 * MS_PER_MINUTE;

    private static final long MAX_RECONNECT_ATTEMPTS = 1_000_000L;
    private static final long DEFAULT_RECONNECT_INITIAL_MS = 500L;
    private static final long MAX_RECONNECT_INITIAL_MS = MS_PER_MINUTE;
    private static final double DEFAULT_RECONNECT_BACKOFF = 2.0;
    private static final double MIN_RECONNECT_BACKOFF = 1.0;
    private static final double MAX_RECONNECT_BACKOFF = 10.0;
    private static final long DEFAULT_RECONNECT_MAX_DELAY_MS = 15 * MS_PER_SECOND;
    private static final long MAX_RECONNECT_MAX_DELAY_MS = 10 * MS_PER_MINUTE;

    private static final long DEFAULT_STALL_AFTER_MS = ScriptRuntime.DEFAULT_STALL_AFTER_MS;
    private static final long MIN_STALL_AFTER_MS = 10 * MS_PER_SECOND;
    private static final long MAX_STALL_AFTER_MS = 60 * MS_PER_MINUTE;

    private static final long DEFAULT_TOAST_SECONDS = 5L;
    private static final long MIN_TOAST_SECONDS = 1L;
    private static final long MAX_TOAST_SECONDS = 120L;

    /** Windows keeps window coordinates in 16 bits; nothing real lies beyond this. */
    private static final long MAX_SCREEN_COORDINATE = 32_767L;
    private static final long MIN_WINDOW_SIDE_PX = 200L;
    private static final long DEFAULT_WINDOW_WIDTH_PX = 1_100L;
    private static final long DEFAULT_WINDOW_HEIGHT_PX = 700L;

    private static final SettingType<Boolean> FLAG = new SettingType.Flag();

    // ── Connecting ──────────────────────────────────────────────────────

    public static final SettingKey<Boolean> AUTO_CONNECT = new SettingKey<>(
            "autoConnect", "Auto-connect",
            "Connect to new BotWithUs pipes as soon as they appear.",
            FLAG, Boolean.TRUE);

    public static final SettingKey<String> PIPE_PREFIX = new SettingKey<>(
            "autoConnectPipes", "Pipe prefix",
            "Scan for pipes whose name starts with this.",
            new SettingType.Text(Pattern.compile("[A-Za-z0-9_.-]{1," + MAX_PIPE_PREFIX_LENGTH + "}"),
                    "1 to " + MAX_PIPE_PREFIX_LENGTH + " letters, digits, '_', '-' or '.'"),
            DEFAULT_PIPE_PREFIX);

    public static final SettingKey<Long> SCAN_INTERVAL_MS = new SettingKey<>(
            "scanIntervalMs", "Scan every",
            "How often, in milliseconds, to look for new pipes while auto-connect is on.",
            new SettingType.WholeNumber(MIN_SCAN_INTERVAL_MS, MAX_SCAN_INTERVAL_MS),
            DEFAULT_SCAN_INTERVAL_MS);

    public static final SettingKey<Long> RPC_TIMEOUT_MS = new SettingKey<>(
            "defaultTimeout", "RPC timeout",
            "Give up on a single call to the game after this many milliseconds.",
            new SettingType.WholeNumber(MIN_RPC_TIMEOUT_MS, MAX_RPC_TIMEOUT_MS),
            DEFAULT_RPC_TIMEOUT_MS);

    // ── Reconnecting ────────────────────────────────────────────────────

    public static final SettingKey<Long> RECONNECT_MAX_ATTEMPTS = new SettingKey<>(
            "reconnect.maxAttempts", "Give up after",
            "Stop retrying after this many attempts. 0 keeps trying until the client comes back.",
            new SettingType.WholeNumber(0L, MAX_RECONNECT_ATTEMPTS), 0L);

    public static final SettingKey<Long> RECONNECT_INITIAL_DELAY_MS = new SettingKey<>(
            "reconnect.initialDelayMs", "First retry after",
            "Milliseconds to wait before the first retry.",
            new SettingType.WholeNumber(0L, MAX_RECONNECT_INITIAL_MS), DEFAULT_RECONNECT_INITIAL_MS);

    public static final SettingKey<Double> RECONNECT_BACKOFF = new SettingKey<>(
            "reconnect.backoff", "Back off",
            "Multiply the wait by this after each failed try.",
            new SettingType.Decimal(MIN_RECONNECT_BACKOFF, MAX_RECONNECT_BACKOFF),
            DEFAULT_RECONNECT_BACKOFF);

    public static final SettingKey<Long> RECONNECT_MAX_DELAY_MS = new SettingKey<>(
            "reconnect.maxDelayMs", "Longest wait",
            "Never wait longer than this many milliseconds between tries.",
            new SettingType.WholeNumber(0L, MAX_RECONNECT_MAX_DELAY_MS), DEFAULT_RECONNECT_MAX_DELAY_MS);

    // ── Scripts ─────────────────────────────────────────────────────────

    public static final SettingKey<Boolean> AUTO_RELOAD = new SettingKey<>(
            "autoReload", "Watch folder",
            "Reload when a JAR in scripts/ or scripts/management/ is added, rebuilt or deleted.",
            FLAG, Boolean.FALSE);

    public static final SettingKey<Boolean> RESTART_AFTER_RELOAD = new SettingKey<>(
            "scripts.restartAfterReload", "Restart after reload",
            "Start reloaded scripts again on the clients that were running them.",
            FLAG, Boolean.FALSE);

    public static final SettingKey<Long> STALL_AFTER_MS = new SettingKey<>(
            "scripts.stallAfterMs", "Mark stalled after",
            "Flag a script when one loop runs longer than this many milliseconds.",
            new SettingType.WholeNumber(MIN_STALL_AFTER_MS, MAX_STALL_AFTER_MS), DEFAULT_STALL_AFTER_MS);

    // ── Notifications ───────────────────────────────────────────────────

    private static final Map<NotificationKind, SettingKey<Boolean>> NOTIFY_ENABLED = notifyKeys();

    public static final SettingKey<Long> NOTIFY_DURATION_S = new SettingKey<>(
            "notify.durationS", "Keep pop-ups for",
            "Seconds a pop-up stays on screen. Errors stay until you close them.",
            new SettingType.WholeNumber(MIN_TOAST_SECONDS, MAX_TOAST_SECONDS), DEFAULT_TOAST_SECONDS);

    // ── Interface ───────────────────────────────────────────────────────

    public static final SettingKey<StartMode> START_MODE = new SettingKey<>(
            "ui.startMode", "Open in",
            "Which view to show when the host starts. F12 switches any time.",
            new SettingType.Choice<>(List.of(StartMode.values())), StartMode.NORMAL);

    public static final SettingKey<TextSize> TEXT_SIZE = new SettingKey<>(
            "ui.textSize", "Text size",
            "Scales every size in the app. Follows Windows display scaling by default.",
            new SettingType.Choice<>(List.of(TextSize.values())), TextSize.MATCH_WINDOWS);

    public static final SettingKey<Boolean> REDUCE_MOTION = new SettingKey<>(
            "ui.reduceMotion", "Reduce motion",
            "Turn off the pulse lanes and slide animations.",
            FLAG, Boolean.FALSE);

    public static final SettingKey<Boolean> NATIVE_FRAME = new SettingKey<>(
            "ui.nativeFrame", "Windows title bar",
            "Use the standard Windows title bar and borders instead of the app's own. "
                    + "Takes effect after a restart.",
            FLAG, Boolean.FALSE);

    // ── Window placement (written by the host as the window moves) ──────

    private static final SettingType<Long> SCREEN_COORDINATE =
            new SettingType.WholeNumber(-MAX_SCREEN_COORDINATE, MAX_SCREEN_COORDINATE);
    private static final SettingType<Long> WINDOW_SIDE =
            new SettingType.WholeNumber(MIN_WINDOW_SIDE_PX, MAX_SCREEN_COORDINATE);

    public static final SettingKey<Long> WINDOW_X = new SettingKey<>(
            "ui.window.x", "Window left",
            "Screen position of the window's left edge when it is not maximised. Saved as you move it.",
            SCREEN_COORDINATE, 0L);

    public static final SettingKey<Long> WINDOW_Y = new SettingKey<>(
            "ui.window.y", "Window top",
            "Screen position of the window's top edge when it is not maximised. Saved as you move it.",
            SCREEN_COORDINATE, 0L);

    public static final SettingKey<Long> WINDOW_WIDTH = new SettingKey<>(
            "ui.window.width", "Window width",
            "Width in pixels when the window is not maximised. Saved as you resize it.",
            WINDOW_SIDE, DEFAULT_WINDOW_WIDTH_PX);

    public static final SettingKey<Long> WINDOW_HEIGHT = new SettingKey<>(
            "ui.window.height", "Window height",
            "Height in pixels when the window is not maximised. Saved as you resize it.",
            WINDOW_SIDE, DEFAULT_WINDOW_HEIGHT_PX);

    public static final SettingKey<Boolean> WINDOW_MAXIMISED = new SettingKey<>(
            "ui.window.maximised", "Window maximised",
            "Whether the window was maximised when it was last moved or resized.",
            FLAG, Boolean.FALSE);

    // ── Diagnostics ─────────────────────────────────────────────────────

    public static final SettingKey<Boolean> COLLECT_RPC_TIMING = new SettingKey<>(
            "diag.collectRpc", "Collect RPC timing",
            "Per-method call counts and latency percentiles.",
            FLAG, Boolean.TRUE);

    public static final SettingKey<Boolean> COLLECT_LOOP_TIMING = new SettingKey<>(
            "diag.collectLoops", "Collect loop timing",
            "Per-script loop times and the last 24 for the pulse lane.",
            FLAG, Boolean.TRUE);

    /** Every known key, in settings-page order. Immutable. */
    public static final List<SettingKey<?>> ALL = catalogue();

    private SettingKeys() {}

    /** The {@code notify.<kind>.enabled} switch for {@code kind}. */
    public static SettingKey<Boolean> notifyEnabled(NotificationKind kind) {
        return NOTIFY_ENABLED.get(kind);
    }

    private static Map<NotificationKind, SettingKey<Boolean>> notifyKeys() {
        Map<NotificationKind, SettingKey<Boolean>> keys = new EnumMap<>(NotificationKind.class);
        for (NotificationKind kind : NotificationKind.values()) {
            keys.put(kind, new SettingKey<>("notify." + kind.id() + ".enabled",
                    kind.label(), kind.description(), FLAG, Boolean.TRUE));
        }
        return Collections.unmodifiableMap(keys);
    }

    private static List<SettingKey<?>> catalogue() {
        List<SettingKey<?>> keys = new ArrayList<>(List.of(
                AUTO_CONNECT, PIPE_PREFIX, SCAN_INTERVAL_MS, RPC_TIMEOUT_MS,
                RECONNECT_MAX_ATTEMPTS, RECONNECT_INITIAL_DELAY_MS, RECONNECT_BACKOFF,
                RECONNECT_MAX_DELAY_MS,
                AUTO_RELOAD, RESTART_AFTER_RELOAD, STALL_AFTER_MS));
        for (NotificationKind kind : NotificationKind.values()) {
            keys.add(notifyEnabled(kind));
        }
        keys.add(NOTIFY_DURATION_S);
        keys.addAll(AlertSettingKeys.ALL);
        keys.addAll(List.of(START_MODE, TEXT_SIZE, REDUCE_MOTION, NATIVE_FRAME,
                WINDOW_X, WINDOW_Y, WINDOW_WIDTH, WINDOW_HEIGHT, WINDOW_MAXIMISED,
                COLLECT_RPC_TIMING, COLLECT_LOOP_TIMING));
        return List.copyOf(keys);
    }
}
