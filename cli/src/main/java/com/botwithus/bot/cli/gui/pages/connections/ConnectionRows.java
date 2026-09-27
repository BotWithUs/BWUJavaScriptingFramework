package com.botwithus.bot.cli.gui.pages.connections;

import com.botwithus.bot.cli.clients.ClientLifecycle;
import com.botwithus.bot.cli.clients.ClientRecord;
import com.botwithus.bot.cli.events.ClientKey;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Builds the Connections table from what the host knows: one row per client in
 * the registry, plus one per scanned pipe the host is not connected to.
 *
 * <p>Connected and reconnecting clients come first, in the order the registry
 * first saw them, so a row does not jump while its client reconnects. Found
 * pipes follow in scan order, then closed clients, the most recently closed
 * first. A row offers only what can work in its state: a client whose game
 * exited offers Forget, never Retry, and nothing that needs a connection is
 * offered once the host has dropped it.</p>
 */
public final class ConnectionRows {

    /**
     * Everything one frame's rows are built from.
     *
     * @param clients        the registry's clients, in the order it first saw them
     * @param scanned        what the last scan from the page found, in scan order
     * @param connectedPipes the pipes the host holds a connection on, live or not
     * @param stats          RPC and uptime numbers per pipe, for live connections
     * @param consoleTarget  the pipe console commands run on
     * @param outputFilter   the pipe the console output is filtered to
     * @param droppedAt      when each not-responding client stopped answering
     * @param now            the frame's time
     */
    public record Reading(List<ClientRecord> clients, List<FoundPipe> scanned, Set<String> connectedPipes,
                          Map<String, LinkStats> stats, Optional<String> consoleTarget,
                          Optional<String> outputFilter, Map<ClientKey, Instant> droppedAt, Instant now) {
        public Reading {
            clients = List.copyOf(clients);
            scanned = List.copyOf(scanned);
            connectedPipes = Set.copyOf(connectedPipes);
            stats = Map.copyOf(stats);
            Objects.requireNonNull(consoleTarget, "consoleTarget");
            Objects.requireNonNull(outputFilter, "outputFilter");
            droppedAt = Map.copyOf(droppedAt);
            Objects.requireNonNull(now, "now");
        }
    }

    private static final Set<RowAction> LIVE_ACTIONS =
            EnumSet.of(RowAction.CONSOLE_TARGET, RowAction.OUTPUT_FILTER, RowAction.DISCONNECT);
    private static final Set<RowAction> RETRYING_ACTIONS = EnumSet.of(RowAction.RETRY_NOW, RowAction.STOP_RETRYING);
    private static final Set<RowAction> STOPPED_ACTIONS = EnumSet.of(RowAction.RETRY_NOW, RowAction.DISCONNECT);

    private ConnectionRows() {
    }

    /** The table's rows, in table order. */
    public static List<ConnectionRow> build(Reading reading) {
        List<ConnectionRow> open = new ArrayList<>();
        List<ConnectionRow> closed = new ArrayList<>();
        Set<String> heldPipes = new HashSet<>(reading.connectedPipes());
        for (ClientRecord client : reading.clients()) {
            ConnectionRow row = clientRow(client, reading);
            if (row.group() == RowGroup.CLOSED) {
                closed.add(row);
            } else {
                open.add(row);
                client.pipe().ifPresent(heldPipes::add);
            }
        }
        closed.sort(Comparator.comparing(ConnectionRows::closedFor));
        List<ConnectionRow> rows = new ArrayList<>(open);
        reading.scanned().stream()
                .filter(found -> !heldPipes.contains(found.pipe()))
                .map(found -> foundRow(found, reading))
                .forEach(rows::add);
        rows.addAll(closed);
        return List.copyOf(rows);
    }

    private static ConnectionRow clientRow(ClientRecord client, Reading reading) {
        LinkState link = linkOf(client, reading);
        RowGroup group = switch (link) {
            case LinkState.Closed _ -> RowGroup.CLOSED;
            case LinkState.Connected _, LinkState.Identifying _, LinkState.Resuming _,
                 LinkState.NotResponding _, LinkState.Found _ -> RowGroup.CONNECTED;
        };
        Optional<String> pipe = client.pipe();
        boolean isHeld = pipe.map(reading.connectedPipes()::contains).orElse(false);
        OptionalInt world = group == RowGroup.CLOSED ? client.lastWorld() : client.gameStatus().world();
        return new ConnectionRow(client.key().value(), group, link, Optional.of(client.key()), pipe,
                client.name(), client.key().accountUuid(), world, client.gameStatus(),
                pipe.map(reading.stats()::get).filter(stats -> isLive(link)),
                isOn(pipe, reading.consoleTarget()), isOn(pipe, reading.outputFilter()), actionsFor(link, isHeld));
    }

    private static ConnectionRow foundRow(FoundPipe found, Reading reading) {
        Optional<String> pipe = Optional.of(found.pipe());
        return new ConnectionRow(found.pipe(), RowGroup.FOUND, new LinkState.Found(), Optional.empty(), pipe,
                found.account(), Optional.empty(), found.game().world(), found.game(), Optional.empty(),
                false, false, EnumSet.of(RowAction.CONNECT));
    }

    private static LinkState linkOf(ClientRecord client, Reading reading) {
        Instant now = reading.now();
        return switch (client.lifecycle()) {
            case ClientLifecycle.Connected _ -> new LinkState.Connected();
            case ClientLifecycle.Identifying _ -> new LinkState.Identifying();
            case ClientLifecycle.Resuming resuming -> new LinkState.Resuming(resuming.previousPipe());
            case ClientLifecycle.NotResponding n -> new LinkState.NotResponding(n.attempt(), n.maxAttempts(),
                    n.nextAttemptAt().map(at -> nonNegative(Duration.between(now, at))),
                    nonNegative(Duration.between(reading.droppedAt().getOrDefault(client.key(), n.since()), now)));
            case ClientLifecycle.Closed closed -> new LinkState.Closed(
                    nonNegative(Duration.between(closed.since(), now)), client.pipe().isPresent());
        };
    }

    /** What a row in {@code link} offers; {@code isHeld} says the host still holds a connection for it. */
    private static Set<RowAction> actionsFor(LinkState link, boolean isHeld) {
        Set<RowAction> offered = switch (link) {
            case LinkState.Connected _, LinkState.Identifying _, LinkState.Resuming _ -> LIVE_ACTIONS;
            case LinkState.NotResponding n -> n.isRetrying() ? RETRYING_ACTIONS : STOPPED_ACTIONS;
            case LinkState.Closed _ -> EnumSet.of(RowAction.FORGET);
            case LinkState.Found _ -> EnumSet.of(RowAction.CONNECT);
        };
        return isHeld || isWithoutConnection(link) ? offered : Set.of();
    }

    /** States whose actions need no connection held for them. */
    private static boolean isWithoutConnection(LinkState link) {
        return switch (link) {
            case LinkState.Closed _, LinkState.Found _ -> true;
            case LinkState.Connected _, LinkState.Identifying _, LinkState.Resuming _,
                 LinkState.NotResponding _ -> false;
        };
    }

    private static boolean isLive(LinkState link) {
        return switch (link) {
            case LinkState.Connected _, LinkState.Identifying _, LinkState.Resuming _ -> true;
            case LinkState.NotResponding _, LinkState.Found _, LinkState.Closed _ -> false;
        };
    }

    private static Duration closedFor(ConnectionRow row) {
        return switch (row.link()) {
            case LinkState.Closed closed -> closed.ago();
            case LinkState.Connected _, LinkState.Identifying _, LinkState.Resuming _,
                 LinkState.NotResponding _, LinkState.Found _ -> Duration.ZERO;
        };
    }

    /** Whether {@code pipe} is the one {@code setting} names; no pipe is never it, even when nothing is named. */
    private static boolean isOn(Optional<String> pipe, Optional<String> setting) {
        return pipe.isPresent() && pipe.equals(setting);
    }

    private static Duration nonNegative(Duration d) {
        return d.isNegative() ? Duration.ZERO : d;
    }
}
