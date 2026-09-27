package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.cli.events.ClientKey;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/** The words and numbers the Groups page shows, kept apart from drawing so they can be checked. */
public final class GroupText {

    /** How many characters of an account UUID the table shows. */
    static final int SHORT_UUID_CHARS = 8;
    private static final String NONE = "—";
    private static final String LIST_SEPARATOR = ", ";
    private static final long SECONDS_PER_MINUTE = 60L;
    private static final long SECONDS_PER_HOUR = 3600L;

    private GroupText() {
    }

    /** The first {@value #SHORT_UUID_CHARS} characters of {@code uuid}, or all of it when it is shorter. */
    public static String shortUuid(String uuid) {
        return uuid.length() <= SHORT_UUID_CHARS ? uuid : uuid.substring(0, SHORT_UUID_CHARS);
    }

    /** The name a member's client shows, else its account UUID. */
    public static String accountOf(MemberFacts facts) {
        return facts.name().orElse(facts.uuid());
    }

    /** The name a client shows, else its account UUID, else its pipe. */
    public static String nameOf(PickableClient client) {
        return client.name().orElseGet(() -> idOf(client.key()));
    }

    /** A client's identity as the dialogs print it: short account UUID, or the pipe for a pipe-keyed client. */
    public static String idOf(ClientKey key) {
        return switch (key) {
            case ClientKey.Account account -> shortUuid(account.uuid())
                    + (account.isRemembered() ? "" : " #" + account.instance());
            case ClientKey.Pipe pipe -> pipe.pipe();
        };
    }

    /** "W84", or a dash for no world. */
    public static String world(OptionalInt world) {
        return world.isPresent() ? "W" + world.getAsInt() : NONE;
    }

    /** A loop time rounded to whole milliseconds, without the unit; a dash when there is none. */
    public static String loopMs(OptionalDouble ms) {
        return ms.isPresent() ? Long.toString(Math.round(ms.getAsDouble())) : NONE;
    }

    /** "1 script" / "3 scripts". */
    public static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    /** {@code names} joined with commas. */
    public static String join(List<String> names) {
        return String.join(LIST_SEPARATOR, names);
    }

    /** The line under a script's name in a member's row. */
    public static String stateLine(ScriptFact fact, MemberLink link) {
        if (fact.state() == ScriptState.QUEUED) {
            return "Starts when the client is back";
        }
        if (!link.isConnected()) {
            return link == MemberLink.CLOSED ? "Client closed" : "Waiting for the client";
        }
        String word = switch (fact.state()) {
            case CRASHED -> "Crashed";
            case CUT_OFF -> "Cut off";
            case STALLED -> "Stalled";
            case RUNNING -> "Running";
            case STOPPED -> "Stopped";
            case QUEUED -> "Queued";
        };
        return fact.detail().isBlank() ? word : word + " · " + fact.detail();
    }

    /** A span as a clock: {@code 0:48}, {@code 42:10} or {@code 2:14:03}. Negative spans read as zero. */
    public static String clock(Duration span) {
        long total = Math.max(0L, span.getSeconds());
        long hours = total / SECONDS_PER_HOUR;
        long minutes = total % SECONDS_PER_HOUR / SECONDS_PER_MINUTE;
        long seconds = total % SECONDS_PER_MINUTE;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }
}
