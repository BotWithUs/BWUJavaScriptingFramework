package com.botwithus.bot.cli.sdnlive;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogCapture;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.runtime.ScriptRunner;

import java.util.Optional;

/**
 * The host as the GUI builds it, connected to one injected game client.
 *
 * <p>A real {@link CliContext} and a real {@link Connection} over the agent's
 * pipe and shared memory, so a delivered script is registered and started by
 * the same runtime a user's would be.
 */
final class LiveHost implements AutoCloseable {

    private final CliContext context;
    private final Connection connection;

    private LiveHost(CliContext context, Connection connection) {
        this.context = context;
        this.connection = connection;
    }

    /**
     * Connects to the agent in game process {@code pid}.
     *
     * @throws IllegalStateException if no connection came up, most often because
     *                               the client is not running or the agent is not injected
     */
    static LiveHost connect(long pid) {
        LogBuffer buffer = new LogBuffer();
        CliContext context = new CliContext(buffer, new LogCapture(buffer));
        String pipe = PipeClient.NAME_PREFIX + pid;
        context.connect(pipe);
        Connection connection = context.getConnections().stream()
                .filter(c -> c.getName().equals(pipe))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("could not connect to " + pipe
                        + "; is the client running with the agent injected?"));
        return new LiveHost(context, connection);
    }

    CliContext context() {
        return context;
    }

    /** The id both screens know this client by. */
    String clientId() {
        return connection.getName();
    }

    /** The runner holding a script of class {@code className}, if one is registered on this client. */
    Optional<ScriptRunner> runnerOf(String className) {
        return connection.getRuntime().getRunners().stream()
                .filter(r -> r.getScript().getClass().getName().equals(className))
                .findFirst();
    }

    @Override
    public void close() {
        connection.getRuntime().stopAll();
        context.disconnectAll(true);
    }
}
