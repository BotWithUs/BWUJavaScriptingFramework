package com.botwithus.bot.core.pipe;

import com.botwithus.bot.core.rpc.RetryPolicy;

/**
 * A PipeClient wrapper that attempts to reconnect on failure.
 */
public class ReconnectablePipeClient extends PipeClient {

    private final String pipeName;
    private RetryPolicy reconnectPolicy = RetryPolicy.DEFAULT;
    private volatile Runnable onReconnect;
    private volatile Runnable onDisconnect;

    public ReconnectablePipeClient(String pipeName) {
        super(pipeName);
        this.pipeName = pipeName;
    }

    public void setReconnectPolicy(RetryPolicy policy) {
        this.reconnectPolicy = policy;
    }

    public void setOnReconnect(Runnable callback) {
        this.onReconnect = callback;
    }

    public void setOnDisconnect(Runnable callback) {
        this.onDisconnect = callback;
    }

    /**
     * Attempts to reconnect to the pipe with backoff.
     *
     * @return true if reconnection succeeded
     */
    public boolean tryReconnect() {
        for (int attempt = 1; attempt <= reconnectPolicy.maxRetries(); attempt++) {
            long delay = reconnectPolicy.delayForAttempt(attempt);
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            try {
                // Try creating a new connection by constructing a new PipeClient
                // If the pipe server is up, this will succeed
                new PipeClient(pipeName).close(); // test connection
                Runnable cb = onReconnect;
                if (cb != null) cb.run();
                return true;
            } catch (PipeException ignored) {
                // Retry
            }
        }
        Runnable cb = onDisconnect;
        if (cb != null) cb.run();
        return false;
    }

    public String getPipeName() {
        return pipeName;
    }
}
