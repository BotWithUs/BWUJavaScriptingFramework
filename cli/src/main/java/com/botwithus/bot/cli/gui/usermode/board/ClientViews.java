package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.cli.clients.ClientLifecycle;
import com.botwithus.bot.cli.clients.ClientRecord;
import com.botwithus.bot.cli.events.ClientKey;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Builds a card's {@link ClientView} from the registry's record of the client
 * and what the board read about it this frame.
 */
final class ClientViews {

    /** What the card says in place of the switch while the account is not known yet. */
    static final String AWAITING_ACCOUNT = "Waiting for account info";
    /** What the card says in place of the switch for a client that has no account to key by. */
    static final String NO_ACCOUNT = "No account to resume";
    /** What the card says in place of the switch when the host keeps no script profiles. */
    static final String NO_PROFILES = "Resume is unavailable";

    /**
     * What the board read about a client this frame.
     *
     * @param runnerRows  one row per runner on the client's pipe, in runtime order
     * @param remembered  the scripts the account's profile will resume, as
     *                    {@link ScriptState.Waiting} rows; empty for a pipe key
     * @param autoStart   the account's "resume after restart" setting; empty
     *                    when the host keeps no profiles
     * @param rpcAvgMs    the pipe's average RPC round trip, if any calls were made
     * @param silentSince when the board first saw the client stop answering,
     *                    if it is not answering now
     */
    record Facts(List<ScriptRow> runnerRows, List<ScriptRow> remembered, Optional<Boolean> autoStart,
                 OptionalDouble rpcAvgMs, Optional<Instant> silentSince) {

        Facts {
            runnerRows = List.copyOf(runnerRows);
            remembered = List.copyOf(remembered);
            Objects.requireNonNull(autoStart, "autoStart");
            Objects.requireNonNull(rpcAvgMs, "rpcAvgMs");
            Objects.requireNonNull(silentSince, "silentSince");
        }
    }

    private ClientViews() {}

    /** The card for {@code record}, measured at {@code now}. */
    static ClientView of(ClientRecord record, Facts facts, Instant now) {
        ClientLifecycle lifecycle = record.lifecycle();
        OptionalInt world = lifecycle.isOpen() ? record.gameStatus().world() : record.lastWorld();
        return new ClientView(record.key(), record.pipe(), record.name(), world, stateOf(record, facts, now),
                rowsOf(lifecycle, facts), resumeOf(record.key(), lifecycle, facts.autoStart()));
    }

    /** Where the client is, with the figures its card shows. */
    static ClientState stateOf(ClientRecord record, Facts facts, Instant now) {
        return switch (record.lifecycle()) {
            case ClientLifecycle.Identifying _ -> new ClientState.Identifying();
            case ClientLifecycle.Connected _ ->
                    new ClientState.Connected(since(record.connectedAt().orElse(now), now), facts.rpcAvgMs());
            case ClientLifecycle.NotResponding silent -> new ClientState.NotResponding(
                    since(facts.silentSince().orElse(silent.since()), now), silent.attempt(), silent.maxAttempts(),
                    silent.nextAttemptAt().map(at -> since(now, at)));
            case ClientLifecycle.Closed closed -> new ClientState.Closed(since(closed.since(), now));
            case ClientLifecycle.Resuming _ -> new ClientState.Resuming();
        };
    }

    /** How long from {@code from} to {@code to}; never negative. */
    private static Duration since(Instant from, Instant to) {
        return to.isAfter(from) ? Duration.between(from, to) : Duration.ZERO;
    }

    /**
     * The rows a card lists. A connected client lists its runners. A client that
     * is not answering or has closed lists what it was running, or failing that
     * what it will resume, all as waiting. A resuming client lists its runners
     * and, when it resumes on its own, the scripts still to start.
     */
    static List<ScriptRow> rowsOf(ClientLifecycle lifecycle, Facts facts) {
        List<ScriptRow> runners = facts.runnerRows();
        List<ScriptRow> remembered = facts.remembered();
        return switch (lifecycle) {
            case ClientLifecycle.Identifying _ -> List.of();
            case ClientLifecycle.Connected _ -> runners;
            case ClientLifecycle.NotResponding _ -> waiting(runners.isEmpty() ? remembered : runners);
            case ClientLifecycle.Closed _ -> waiting(remembered.isEmpty() ? runners : remembered);
            case ClientLifecycle.Resuming _ -> facts.autoStart().orElse(false)
                    ? withStillToStart(runners, remembered) : runners;
        };
    }

    private static List<ScriptRow> waiting(List<ScriptRow> rows) {
        return rows.stream().map(row -> row.withState(new ScriptState.Waiting())).toList();
    }

    /** {@code runners}, then each remembered script none of them is. */
    private static List<ScriptRow> withStillToStart(List<ScriptRow> runners, List<ScriptRow> remembered) {
        Set<String> present = new HashSet<>();
        runners.forEach(row -> present.add(row.name()));
        List<ScriptRow> rows = new ArrayList<>(runners);
        remembered.stream().filter(row -> !present.contains(row.name())).forEach(rows::add);
        return rows;
    }

    /** The "Resume after restart" switch for a client under {@code key}. */
    static ResumeSwitch resumeOf(ClientKey key, ClientLifecycle lifecycle, Optional<Boolean> autoStart) {
        return switch (key) {
            case ClientKey.Account _ -> autoStart.<ResumeSwitch>map(ResumeSwitch.Available::new)
                    .orElseGet(() -> new ResumeSwitch.Unavailable(NO_PROFILES));
            case ClientKey.Pipe _ -> new ResumeSwitch.Unavailable(switch (lifecycle) {
                case ClientLifecycle.Identifying _ -> AWAITING_ACCOUNT;
                case ClientLifecycle.Connected _, ClientLifecycle.NotResponding _, ClientLifecycle.Closed _,
                     ClientLifecycle.Resuming _ -> NO_ACCOUNT;
            });
        };
    }
}
