package com.botwithus.bot.cli.gui.pages.connections;

import com.botwithus.bot.cli.GameState;
import com.botwithus.bot.cli.GameStatus;
import com.botwithus.bot.core.rpc.ReconnectPolicy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Locale;
import java.util.OptionalInt;

/**
 * The words and numbers the Connections page prints, in one place so the table,
 * the detail pane and the preview all say the same thing the same way.
 */
public final class ConnectionText {

    /** What a cell with nothing to say shows. */
    public static final String NONE = "—";

    private static final int UUID_SHORT = 8;
    private static final long SECONDS_PER_MINUTE = 60L;
    private static final long MINUTES_PER_HOUR = 60L;
    private static final long HOURS_PER_DAY = 24L;
    private static final long MS_PER_SECOND = 1_000L;
    private static final long SECONDS_PER_HOUR = SECONDS_PER_MINUTE * MINUTES_PER_HOUR;
    private static final String PIPE_GLOB = "*";

    private ConnectionText() {
    }

    // ── Game ───────────────────────────────────────────────────────────────

    /** The Game column: where the client is, capitalised. */
    public static String game(GameStatus status) {
        return switch (status.state()) {
            case IN_GAME -> "Logged in";
            case LOBBY -> "Lobby";
            case LOGIN_SCREEN -> "Login screen";
            case UNKNOWN -> NONE;
        };
    }

    /** Whether the Game column adds a small "members" after "Logged in". */
    public static boolean isMemberNote(GameStatus status) {
        return status.state() == GameState.IN_GAME && status.isMember();
    }

    /** The detail pane's game line, such as "logged in · members". */
    public static String gameLong(GameStatus status) {
        return switch (status.state()) {
            case IN_GAME -> status.isMember() ? "logged in · members" : "logged in";
            case LOBBY -> "lobby";
            case LOGIN_SCREEN -> "login screen";
            case UNKNOWN -> NONE;
        };
    }

    /** "W84", or a dash with no world. */
    public static String world(OptionalInt world) {
        return world.isPresent() ? "W" + world.getAsInt() : NONE;
    }

    /** The first eight characters of an account UUID, which is how the table tells accounts apart. */
    public static String shortUuid(String uuid) {
        return uuid.length() <= UUID_SHORT ? uuid : uuid.substring(0, UUID_SHORT);
    }

    // ── Time ───────────────────────────────────────────────────────────────

    /** How long a pipe has been up: "2h 14m", then "1d 04h" past a day. */
    public static String uptime(Duration d) {
        long minutes = d.toMinutes();
        long hours = minutes / MINUTES_PER_HOUR;
        if (hours < HOURS_PER_DAY) {
            return String.format(Locale.ROOT, "%dh %02dm", hours, minutes % MINUTES_PER_HOUR);
        }
        return String.format(Locale.ROOT, "%dd %02dh", hours / HOURS_PER_DAY, hours % HOURS_PER_DAY);
    }

    /** How long a client has not answered, as a clock: "0:42", "1:02:03". */
    public static String downFor(Duration d) {
        long total = Math.max(0L, d.toSeconds());
        long hours = total / SECONDS_PER_HOUR;
        long minutes = total % SECONDS_PER_HOUR / SECONDS_PER_MINUTE;
        long seconds = total % SECONDS_PER_MINUTE;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    /** The table's "3m ago". */
    public static String ago(Duration d) {
        return agoIn(d, "m ago", "h ago", "d ago");
    }

    /** The detail pane's "3 min ago". */
    public static String agoLong(Duration d) {
        return agoIn(d, " min ago", " h ago", " days ago");
    }

    private static String agoIn(Duration d, String minutes, String hours, String days) {
        if (d.toMinutes() < 1) {
            return "just now";
        }
        if (d.toHours() < 1) {
            return d.toMinutes() + minutes;
        }
        if (d.toDays() < 1) {
            return d.toHours() + hours;
        }
        return d.toDays() + days;
    }

    /** A wait in whole seconds, rounded up so it never reads "0 s" while still waiting. */
    public static String seconds(Duration d) {
        if (d.isNegative() || d.isZero()) {
            return "now";
        }
        long whole = (d.toMillis() + MS_PER_SECOND - 1) / MS_PER_SECOND;
        return whole + " s";
    }

    // ── Link ───────────────────────────────────────────────────────────────

    /** The mean RPC round trip: "2.8 ms". */
    public static String rpc(double ms) {
        return String.format(Locale.ROOT, "%.1f ms", ms);
    }

    /** A count with thousands separators: "18,204". */
    public static String count(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    /** "4 · no limit", or "4 of 10" with a budget. */
    public static String attempt(int attempt, OptionalInt max) {
        return max.isPresent() ? attempt + " of " + max.getAsInt() : attempt + " · no limit";
    }

    /** The Link column. */
    public static String link(LinkState state) {
        return switch (state) {
            case LinkState.Connected _ -> "Connected";
            case LinkState.Identifying _ -> "Identifying…";
            case LinkState.Resuming _ -> "Resuming";
            case LinkState.NotResponding n -> n.nextIn()
                    .map(next -> "Retry " + n.attempt() + " · " + seconds(next))
                    .orElse("Not retrying");
            case LinkState.Found _ -> "Not connected";
            case LinkState.Closed _ -> "Client closed";
        };
    }

    /**
     * The reconnect back-off in words: "0.5 s × 2, max 15 s", with ", 10 tries"
     * when there is a budget, and "5 s, every time" when the wait never grows.
     */
    public static String policy(ReconnectPolicy policy) {
        String first = span(policy.initialDelayMs());
        String backOff = policy.backoffMultiplier() <= 1.0 || policy.maxDelayMs() == policy.initialDelayMs()
                ? first + ", every time"
                : first + " × " + plain(policy.backoffMultiplier()) + ", max " + span(policy.maxDelayMs());
        OptionalInt limit = policy.attemptLimit();
        return limit.isPresent() ? backOff + ", " + limit.getAsInt() + " tries" : backOff;
    }

    /** A delay as the policy line writes it: "0.5 s", "15 s", "1 min", "1.5 min". */
    private static String span(long ms) {
        long perMinute = SECONDS_PER_MINUTE * MS_PER_SECOND;
        if (ms >= perMinute) {
            return plain((double) ms / perMinute) + " min";
        }
        return plain((double) ms / MS_PER_SECOND) + " s";
    }

    /** A number with no trailing zeros: 2, 1.5, 0.5. */
    private static String plain(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    // ── Header ─────────────────────────────────────────────────────────────

    /** The prefix as the field shows it: a glob. */
    public static String pipeGlob(String prefix) {
        return prefix + PIPE_GLOB;
    }

    /** The prefix as a pipe path pattern, for the empty state's hint. */
    public static String pipePath(String prefix) {
        return "\\\\.\\pipe\\" + pipeGlob(prefix);
    }

    /** The line beside the title when no scan from the page is running. */
    public static String autoConnectLine(boolean isOn, Duration every) {
        if (!isOn) {
            return "Auto-connect off · scan by hand";
        }
        return "Auto-connect · scanning every " + plain((double) every.toMillis() / MS_PER_SECOND) + " s";
    }
}
