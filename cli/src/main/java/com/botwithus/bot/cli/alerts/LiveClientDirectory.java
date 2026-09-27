package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.clients.ClientLifecycle;
import com.botwithus.bot.cli.clients.ClientRecord;
import com.botwithus.bot.cli.events.ClientRef;
import com.botwithus.bot.core.rpc.ReconnectController;
import com.botwithus.bot.core.runtime.ScriptRunner;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ClientDirectory} over the host's {@link com.botwithus.bot.cli.clients.ClientRegistry}
 * (names, lifecycle, remembered clients) and its connection table (running
 * scripts, whether a game exited).
 */
public final class LiveClientDirectory implements ClientDirectory {

    /** Shown for a client with no name yet and no pipe: never its account id. */
    static final String UNNAMED = "A client";

    private final CliContext ctx;

    public LiveClientDirectory(CliContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
    }

    @Override
    public Optional<String> displayName(ClientRef client) {
        return ctx.getClientRegistry().get(client.key()).flatMap(ClientRecord::name);
    }

    @Override
    public boolean hasExited(ClientRef client) {
        return connection(client.pipe())
                .map(Connection::getReconnectController)
                .map(ReconnectController::isClientGone)
                .orElse(false);
    }

    @Override
    public List<ClientSnapshot> clients() {
        return ctx.getClientRegistry().clients().stream().map(this::snapshot).toList();
    }

    private ClientSnapshot snapshot(ClientRecord record) {
        Optional<Connection> conn = record.pipe().flatMap(this::connection);
        int running = conn.map(c -> (int) c.getRuntime().getRunners().stream()
                .filter(ScriptRunner::isRunning).count()).orElse(0);
        String name = record.name().or(record::pipe).orElse(UNNAMED);
        return new ClientSnapshot(record.key(), name, stateOf(record.lifecycle()), record.connectedAt(), running);
    }

    private static ClientSnapshot.State stateOf(ClientLifecycle lifecycle) {
        return switch (lifecycle) {
            case ClientLifecycle.Identifying _, ClientLifecycle.Connected _, ClientLifecycle.Resuming _ ->
                    ClientSnapshot.State.ONLINE;
            case ClientLifecycle.NotResponding _ -> ClientSnapshot.State.NOT_RESPONDING;
            case ClientLifecycle.Closed _ -> ClientSnapshot.State.CLOSED;
        };
    }

    private Optional<Connection> connection(String pipe) {
        return ctx.getConnections().stream().filter(conn -> conn.getName().equals(pipe)).findFirst();
    }
}
