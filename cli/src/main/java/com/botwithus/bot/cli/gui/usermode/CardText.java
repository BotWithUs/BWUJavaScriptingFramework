package com.botwithus.bot.cli.gui.usermode;

import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.gui.usermode.board.ClientState;
import com.botwithus.bot.cli.gui.usermode.board.ClientView;
import com.botwithus.bot.cli.gui.usermode.board.ResumeSwitch;
import com.botwithus.bot.cli.gui.usermode.board.ScriptRow;
import com.botwithus.bot.cli.gui.usermode.board.ScriptState;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Every piece of text a client card shows, worked out from its view with no
 * drawing, so the wording can be tested without a GL context.
 */
final class CardText {

    /** The status chip in a card's top-right corner. */
    record Chip(String label, CardTone tone) { }

    /**
     * One figure in a connected card's stats row.
     *
     * @param unit the smaller text after the value, possibly empty
     */
    record Stat(String label, String value, String unit) { }

    /** The line that replaces the stats while the client is not connected. */
    record Note(CardTone tone, String title, String body) { }

    /**
     * A script row's second line.
     *
     * @param wraps whether the line may wrap onto more lines rather than be cut short
     */
    record Meta(String text, CardTone tone, boolean wraps) { }

    /** Shown for a client whose account name is not known yet. */
    static final String NEW_CLIENT = "New client";
    /** How many characters of the account UUID a card shows. */
    static final int SHORT_UUID = 8;

    private static final String DASH = "—";
    private static final String SEP = " · ";
    private static final DateTimeFormatter CRASH_TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final Chip IDLE = new Chip("Idle", CardTone.IDLE);
    private static final int RANK_RUNNING = 1;
    private static final int RANK_STALLED = 2;
    private static final int RANK_CRASHED = 3;
    private static final int RANK_CUT_OFF = 4;

    private CardText() {}

    /** The account name, or {@link #NEW_CLIENT}. */
    static String title(ClientView view) {
        return view.account().orElse(NEW_CLIENT);
    }

    /**
     * The line under the name: the short account UUID and the world, e.g.
     * "3f9a1c2e · World 84", or the pipe for a client with no account.
     */
    static String subLine(ClientView view) {
        return switch (view.id()) {
            case ClientKey.Account account -> {
                String uuid = account.uuid();
                String shortId = uuid.substring(0, Math.min(SHORT_UUID, uuid.length()));
                yield view.world().isPresent() ? shortId + SEP + "World " + view.world().getAsInt() : shortId;
            }
            case ClientKey.Pipe pipe -> "pipe " + pipe.pipe();
        };
    }

    static Chip chip(ClientView view) {
        return switch (view.state()) {
            case ClientState.Identifying _ -> new Chip("Identifying", CardTone.INFO);
            case ClientState.NotResponding _ -> new Chip("Not responding", CardTone.WARN);
            case ClientState.Closed _ -> new Chip("Client closed", CardTone.IDLE);
            case ClientState.Resuming _ -> new Chip("Resuming", CardTone.INFO);
            case ClientState.Connected _ -> view.scripts().stream()
                    .map(row -> scriptChip(row.state()))
                    .flatMap(Optional::stream)
                    .max(Comparator.comparingInt(RankedChip::rank))
                    .map(RankedChip::chip)
                    .orElse(IDLE);
        };
    }

    /** A connected card's chip is its worst script's; a higher rank is worse. */
    private record RankedChip(int rank, Chip chip) { }

    private static Optional<RankedChip> scriptChip(ScriptState state) {
        return switch (state) {
            case ScriptState.CutOff _ -> Optional.of(new RankedChip(RANK_CUT_OFF, new Chip("Script cut off", CardTone.ERR)));
            case ScriptState.Crashed _ -> Optional.of(new RankedChip(RANK_CRASHED, new Chip("Script crashed", CardTone.ERR)));
            case ScriptState.Stalled _ -> Optional.of(new RankedChip(RANK_STALLED, new Chip("Stalled", CardTone.WARN)));
            case ScriptState.Running _ -> Optional.of(new RankedChip(RANK_RUNNING, new Chip("Running", CardTone.RUN)));
            case ScriptState.Stopped _, ScriptState.Waiting _ -> Optional.empty();
        };
    }

    /** Online, RPC and Scripts, for a connected card. */
    static List<Stat> stats(ClientView view, ClientState.Connected connected) {
        Stat rpc = connected.rpcAvgMs().isPresent()
                ? new Stat("RPC", String.format(Locale.ROOT, "%.1f", connected.rpcAvgMs().getAsDouble()), "ms")
                : new Stat("RPC", DASH, "");
        return List.of(
                new Stat("Online", online(connected.online()), ""),
                rpc,
                new Stat("Scripts", String.valueOf(view.runningCount()),
                        "of " + view.scripts().size() + " running"));
    }

    /** The note for a card that is not connected; empty for a connected one. */
    static Optional<Note> note(ClientView view) {
        return switch (view.state()) {
            case ClientState.Connected _ -> Optional.empty();
            case ClientState.Identifying _ -> Optional.of(new Note(CardTone.INFO, "Reading the account…",
                    "A new game client opened this pipe. Once we know the account, its card and scripts "
                            + "appear here."));
            case ClientState.NotResponding silent -> Optional.of(new Note(CardTone.WARN,
                    "No reply for " + runtime(silent.silentFor()), retrying(silent)));
            case ClientState.Closed closed -> Optional.of(new Note(CardTone.IDLE,
                    "Game client closed " + ago(closed.closedFor()), afterClose(view.resume())));
            case ClientState.Resuming _ -> Optional.of(new Note(CardTone.INFO, "Same account is back",
                    "Matched by UUID on a new pipe. Restarting the scripts it was running."));
        };
    }

    private static String retrying(ClientState.NotResponding silent) {
        if (silent.nextIn().isEmpty()) {
            return "Stopped retrying after attempt " + silent.attempt() + ". Retry when the game is back.";
        }
        String cap = silent.maxAttempts().isPresent() ? " of " + silent.maxAttempts().getAsInt() : "";
        return "Retrying: attempt " + silent.attempt() + cap + ", next in " + wholeSeconds(silent.nextIn().get())
                + " s. The game may be frozen or restarting.";
    }

    /** Whole seconds, rounded up, and at least one: a retry is never "next in 0 s". */
    private static long wholeSeconds(Duration wait) {
        long seconds = wait.toSeconds() + (wait.toNanosPart() > 0 ? 1 : 0);
        return Math.max(1L, seconds);
    }

    private static String afterClose(ResumeSwitch resume) {
        return switch (resume) {
            case ResumeSwitch.Available available -> available.isOn()
                    ? "When this account logs in again, its scripts restart on their own."
                    : "Resume after restart is off, so nothing will start when it comes back.";
            case ResumeSwitch.Unavailable _ -> "It had no account to resume, so nothing will start on its own.";
        };
    }

    /** A row's second line, which for a waiting script depends on its client. */
    static Meta meta(ScriptRow row, ClientState client, ResumeSwitch resume) {
        return switch (row.state()) {
            case ScriptState.Running running -> new Meta(runningMeta(running, row.avgLoopMs()), CardTone.IDLE, false);
            case ScriptState.Stopped _ -> new Meta("Stopped", CardTone.IDLE, false);
            case ScriptState.Stalled stalled -> new Meta("Not responding · " + stalled.inLoop().toSeconds()
                    + " s in onLoop()", CardTone.WARN, true);
            case ScriptState.Crashed crashed -> new Meta(crashed.summary() + SEP + CRASH_TIME.format(crashed.at()),
                    CardTone.ERR, true);
            case ScriptState.CutOff _ -> new Meta("Ignored Stop, so it was cut off from the game. It stays "
                    + "quarantined until you restart BotWithUs.", CardTone.ERR, true);
            case ScriptState.Waiting _ -> waitingMeta(client, resume);
        };
    }

    private static String runningMeta(ScriptState.Running running, double avgLoopMs) {
        String runtime = runtime(running.runningFor());
        return avgLoopMs > 0 ? runtime + SEP + Math.round(avgLoopMs) + " ms/loop" : runtime;
    }

    private static Meta waitingMeta(ClientState client, ResumeSwitch resume) {
        return switch (client) {
            case ClientState.Resuming _ -> new Meta("Starting…", CardTone.INFO, false);
            case ClientState.Closed _ -> new Meta(resume.isOn() ? "Resumes when this account is back"
                    : "Won’t resume on its own", CardTone.IDLE, false);
            case ClientState.NotResponding _, ClientState.Identifying _, ClientState.Connected _ ->
                    new Meta("Waiting for the client", CardTone.IDLE, false);
        };
    }

    /** Time online, e.g. "3h 12m" or "0h 03m". */
    static String online(Duration online) {
        return String.format(Locale.ROOT, "%dh %02dm", online.toHours(), online.toMinutesPart());
    }

    /** How long a run has lasted, e.g. "41:07" or "1:12:30". */
    static String runtime(Duration runtime) {
        long hours = runtime.toHours();
        if (hours > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, runtime.toMinutesPart(),
                    runtime.toSecondsPart());
        }
        return String.format(Locale.ROOT, "%d:%02d", runtime.toMinutes(), runtime.toSecondsPart());
    }

    /** How long ago a client closed, e.g. "just now" or "3 min ago". */
    static String ago(Duration ago) {
        if (ago.toMinutes() < 1) {
            return "just now";
        }
        if (ago.toHours() < 1) {
            return ago.toMinutes() + " min ago";
        }
        if (ago.toDays() < 1) {
            return ago.toHours() + " h ago";
        }
        long days = ago.toDays();
        return days + (days == 1 ? " day ago" : " days ago");
    }
}
