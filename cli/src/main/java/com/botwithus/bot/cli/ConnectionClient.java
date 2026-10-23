package com.botwithus.bot.cli;

import com.botwithus.bot.cli.clients.LiveClient;
import com.botwithus.bot.core.rpc.ReconnectController;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A {@link Connection} as the client registry reads it. Every read is of a
 * volatile field or the reconnect controller's own state, so it is safe on any
 * thread and never touches the pipe.
 */
record ConnectionClient(Connection conn) implements LiveClient {

    @Override
    public Instant connectedAt() {
        return conn.getConnectedAt();
    }

    @Override
    public Optional<String> displayName() {
        return conn.getDisplayName();
    }

    @Override
    public GameStatus gameStatus() {
        return conn.getGameStatus();
    }

    @Override
    public OptionalInt maxAttempts() {
        ReconnectController controller = conn.getReconnectController();
        return controller != null ? controller.maxAttempts() : OptionalInt.empty();
    }

    @Override
    public boolean isClientGone() {
        ReconnectController controller = conn.getReconnectController();
        return controller != null && controller.isClientGone();
    }
}
