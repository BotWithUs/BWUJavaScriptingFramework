package com.botwithus.bot.core.pipe;

import com.botwithus.bot.core.rpc.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link PipeClient} that can rebuild its underlying transport after a
 * connection failure without forcing callers to hold a new instance.
 */
public class ReconnectablePipeClient extends PipeClient {

    private static final Logger log = LoggerFactory.getLogger(ReconnectablePipeClient.class);

    private final String pipeName;
    private volatile RetryPolicy reconnectPolicy = RetryPolicy.DEFAULT;
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
     * Attempts to reconnect, replacing the broken transport on success.
     * Tries immediately first, then applies {@link RetryPolicy} backoff.
     *
     * <p>The caller must guarantee no other thread is using this pipe while
     * {@code tryReconnect} runs — typically by holding the same lock used for
     * normal send/receive (e.g. {@code RpcClient}'s pipe lock).</p>
     *
     * @return true if a fresh transport is in place; false if all attempts failed
     */
    public boolean tryReconnect() {
        int attempts = Math.max(1, reconnectPolicy.maxRetries());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                Transport next = openTransport(getPipePath());
                swapTransport(next);
                Runnable cb = onReconnect;
                if (cb != null) {
                    try { cb.run(); } catch (RuntimeException ex) {
                        log.warn("onReconnect callback threw: {}", ex.getMessage());
                    }
                }
                return true;
            } catch (PipeException e) {
                if (attempt >= attempts) break;
                long delay = reconnectPolicy.delayForAttempt(attempt);
                if (delay > 0) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        Runnable cb = onDisconnect;
        if (cb != null) {
            try { cb.run(); } catch (RuntimeException ex) {
                log.warn("onDisconnect callback threw: {}", ex.getMessage());
            }
        }
        return false;
    }

    public String getPipeName() {
        return pipeName;
    }
}
