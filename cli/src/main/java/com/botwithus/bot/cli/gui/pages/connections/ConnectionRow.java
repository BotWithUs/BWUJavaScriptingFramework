package com.botwithus.bot.cli.gui.pages.connections;

import com.botwithus.bot.cli.GameStatus;
import com.botwithus.bot.cli.events.ClientKey;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * One row of the Connections table, as it stood when the frame's view was
 * built. Immutable.
 *
 * @param id               stable while the row exists: the client's key, or the
 *                         found pipe's name
 * @param key              the client's key; empty for a found pipe the host has
 *                         not connected to
 * @param pipe             the pipe the client is on; empty once it has closed
 * @param account          the account's display name, if known
 * @param accountUuid      the account UUID; empty until the host has read it, and
 *                         for a client that reports none
 * @param world            the world the client is in; for a closed client, the
 *                         last one it was seen in
 * @param stats            RPC and uptime numbers while the pipe is up
 * @param isConsoleTarget  console commands run on this connection
 * @param isOutputFilter   the console shows only this connection's output
 * @param actions          what the row offers now
 */
public record ConnectionRow(String id, RowGroup group, LinkState link, Optional<ClientKey> key,
                            Optional<String> pipe, Optional<String> account, Optional<String> accountUuid,
                            OptionalInt world, GameStatus game, Optional<LinkStats> stats,
                            boolean isConsoleTarget, boolean isOutputFilter, Set<RowAction> actions) {

    public ConnectionRow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(link, "link");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(pipe, "pipe");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(accountUuid, "accountUuid");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(game, "game");
        Objects.requireNonNull(stats, "stats");
        actions = Set.copyOf(actions);
    }

    /** Whether the row offers {@code action} now. */
    public boolean can(RowAction action) {
        return actions.contains(action);
    }

    /** Whether the row needs a look: reconnecting, stopped, or closed. */
    public boolean isProblem() {
        return switch (link) {
            case LinkState.NotResponding _, LinkState.Closed _ -> true;
            case LinkState.Connected _, LinkState.Identifying _, LinkState.Resuming _, LinkState.Found _ -> false;
        };
    }

    /** Whether the pipe dropped and the client has not come back: reconnecting, or stopped. */
    public boolean isNotResponding() {
        return switch (link) {
            case LinkState.NotResponding _ -> true;
            case LinkState.Connected _, LinkState.Identifying _, LinkState.Resuming _, LinkState.Found _,
                 LinkState.Closed _ -> false;
        };
    }

    /** The name to call the client by in a sentence: its account, else its pipe, else its key. */
    public String label() {
        return account.or(() -> pipe).orElse(id);
    }

    /** Case-insensitive match on account, pipe or UUID; a blank query matches everything. */
    public boolean matches(String query) {
        if (query.isBlank()) {
            return true;
        }
        String needle = query.strip().toLowerCase(Locale.ROOT);
        String haystack = account.orElse("") + ' ' + pipe.orElse("") + ' ' + accountUuid.orElse("");
        return haystack.toLowerCase(Locale.ROOT).contains(needle);
    }
}
