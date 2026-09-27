package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.cli.events.ClientKey;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One client card's worth of data, rebuilt every frame: one card per account,
 * however many times its game client restarts.
 *
 * @param id      the client's key: its account, or its pipe when it has none
 * @param pipe    the pipe the client is on; empty once it has closed
 * @param account the name the client shows; empty until one is known
 * @param world   the world to show: the one the client is in, or for a closed
 *                client the one it was last seen in
 * @param state   where the client is
 * @param scripts one row per script, in the order the runtime lists them
 * @param resume  the "Resume after restart" switch
 */
public record ClientView(ClientKey id, Optional<String> pipe, Optional<String> account, OptionalInt world,
                         ClientState state, List<ScriptRow> scripts, ResumeSwitch resume) {

    public ClientView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(pipe, "pipe");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(resume, "resume");
        scripts = List.copyOf(scripts);
    }

    /** How many of the card's scripts are running. */
    public int runningCount() {
        return (int) scripts.stream().filter(row -> row.state().isRunning()).count();
    }

    /** Connected with at least one script running. */
    public boolean isRunning() {
        return state.isConnected() && runningCount() > 0;
    }

    /** Not responding, or connected with a script that stalled, crashed or was cut off. */
    public boolean needsAttention() {
        return switch (state) {
            case ClientState.NotResponding _ -> true;
            case ClientState.Connected _ -> scripts.stream().anyMatch(row -> row.state().needsAttention());
            case ClientState.Identifying _, ClientState.Closed _, ClientState.Resuming _ -> false;
        };
    }

    /** A closed client, waiting for its account to come back. */
    public boolean isClosed() {
        return switch (state) {
            case ClientState.Closed _ -> true;
            case ClientState.Identifying _, ClientState.Connected _, ClientState.NotResponding _,
                 ClientState.Resuming _ -> false;
        };
    }

    /** The row for {@code scriptName}, if the card has one. */
    public Optional<ScriptRow> script(String scriptName) {
        return scripts.stream().filter(row -> row.name().equals(scriptName)).findFirst();
    }

    /** Case-insensitive match on account, script names, account UUID or pipe name. */
    public boolean matches(String query) {
        if (query.isBlank()) {
            return true;
        }
        String needle = query.strip().toLowerCase(Locale.ROOT);
        StringBuilder haystack = new StringBuilder(account.orElse(""));
        scripts.forEach(row -> haystack.append(' ').append(row.name()));
        id.accountUuid().ifPresent(uuid -> haystack.append(' ').append(uuid));
        pipe.ifPresent(name -> haystack.append(' ').append(name));
        return haystack.toString().toLowerCase(Locale.ROOT).contains(needle);
    }
}
