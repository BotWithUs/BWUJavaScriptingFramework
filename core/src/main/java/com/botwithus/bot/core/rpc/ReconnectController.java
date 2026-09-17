package com.botwithus.bot.core.rpc;

import com.botwithus.bot.api.event.ConnectionLostEvent;
import com.botwithus.bot.api.event.GameEvent;
import com.botwithus.bot.api.event.ReconnectStateChangedEvent;
import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.pipe.PipeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Wraps an {@link RpcClient} + {@link PipeClient} pair and recovers transient
 * pipe drops by reopening the transport and restarting the reader loop. The
 * controller is composed over the two clients via constructor injection — it
 * does not subclass either.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #arm} wires {@code rpc.setDisconnectHandler} to this controller.</li>
 *   <li>On disconnect, a virtual thread runs {@link #recover} which transitions
 *       the state machine ({@link ReconnectState}) and retries with the
 *       backoff defined by {@link ReconnectPolicy}.</li>
 *   <li>{@link #close} marks the controller stopped; an in-flight retry will
 *       observe the flag at its next iteration and exit without further
 *       transitions.</li>
 * </ol>
 *
 * <p>Both callbacks ({@code stateListener} and {@code eventSink}) are
 * constructor-injected as {@code Consumer<...>} parameters — no inheritance,
 * no statics.</p>
 */
public final class ReconnectController implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReconnectController.class);

    /**
     * Functional seam for the actual reconnect operation. Production wires
     * this to {@code rpc::reconnect}; tests substitute a fake that fails N
     * times before succeeding.
     */
    @FunctionalInterface
    public interface Reconnector {
        void reconnect() throws PipeException;
    }

    /**
     * Functional seam for installing the disconnect handler. Production wires
     * this to {@code rpc::setDisconnectHandler}; tests can pass a no-op when
     * they invoke {@link #onDisconnect} directly.
     */
    @FunctionalInterface
    public interface DisconnectArmer {
        void arm(Consumer<Throwable> handler);
    }

    private final DisconnectArmer disconnectArmer;
    private final Reconnector reconnector;
    private final String connectionName;
    private final ReconnectPolicy policy;
    private final Consumer<ReconnectState> stateListener;
    private final Consumer<GameEvent> eventSink;
    private final AtomicReference<ReconnectState> stateRef =
            new AtomicReference<>(new ReconnectState.Connected(System.currentTimeMillis()));
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private volatile Thread recoveryThread;

    /**
     * Production wiring: wraps the supplied {@link RpcClient} and
     * {@link PipeClient}. The {@code pipe} reference is documented in the
     * signature per the plan's seam (composition over both) but the recovery
     * loop drives reconnect exclusively through {@link RpcClient#reconnect}
     * so the underlying lock + reader restart stay co-located there.
     */
    public ReconnectController(RpcClient rpc, PipeClient pipe, String pipeName,
                               String connectionName, ReconnectPolicy policy,
                               Consumer<ReconnectState> stateListener,
                               Consumer<GameEvent> eventSink) {
        this(rpc::setDisconnectHandler, () -> rpc.reconnect(pipeName),
                connectionName, policy, stateListener, eventSink);
    }

    /**
     * Function-seam constructor for tests and bespoke wiring. Production
     * code uses the {@link #ReconnectController(RpcClient, PipeClient, String,
     * String, ReconnectPolicy, Consumer, Consumer)} form.
     */
    public ReconnectController(DisconnectArmer disconnectArmer, Reconnector reconnector,
                               String connectionName, ReconnectPolicy policy,
                               Consumer<ReconnectState> stateListener,
                               Consumer<GameEvent> eventSink) {
        this.disconnectArmer = disconnectArmer;
        this.reconnector = reconnector;
        this.connectionName = connectionName;
        this.policy = policy;
        this.stateListener = stateListener;
        this.eventSink = eventSink;
    }

    /**
     * Arms the controller by installing {@link #onDisconnect} on the wrapped
     * {@link RpcClient}. Call after the initial connect succeeds.
     */
    public void arm() {
        disconnectArmer.arm(this::onDisconnect);
    }

    /**
     * Test seam: trigger the recovery loop synchronously on the calling
     * thread, bypassing the virtual-thread fan-out. Production callers
     * receive the disconnect via the armed handler instead.
     */
    void onDisconnectSync(Throwable cause) {
        if (stopped.get()) {
            return;
        }
        publishLost(cause);
        transition(new ReconnectState.Disconnected(System.currentTimeMillis(), cause));
        recover(cause);
    }

    public ReconnectState currentState() {
        return stateRef.get();
    }

    @Override
    public void close() {
        stopped.set(true);
        Thread t = this.recoveryThread;
        if (t != null) {
            t.interrupt();
        }
    }

    private void onDisconnect(Throwable cause) {
        if (stopped.get()) {
            return;
        }
        publishLost(cause);
        transition(new ReconnectState.Disconnected(System.currentTimeMillis(), cause));
        Thread t = Thread.ofVirtual()
                .name("reconnect-" + connectionName)
                .start(() -> recover(cause));
        this.recoveryThread = t;
    }

    private void recover(Throwable initialCause) {
        Throwable lastCause = initialCause;
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            if (stopped.get()) {
                return;
            }
            long delay = policy.delayForAttempt(attempt);
            transition(new ReconnectState.Reconnecting(System.currentTimeMillis(), attempt, delay));
            if (!sleep(delay)) {
                return;
            }
            try {
                reconnector.reconnect();
                transition(new ReconnectState.Connected(System.currentTimeMillis()));
                log.info("Reconnect succeeded for '{}' on attempt {}", connectionName, attempt);
                return;
            } catch (PipeException e) {
                lastCause = e;
                log.warn("Reconnect attempt {} for '{}' failed: {}", attempt, connectionName, e.getMessage());
            }
        }
        if (!stopped.get()) {
            transition(new ReconnectState.GivingUp(System.currentTimeMillis(),
                    policy.maxAttempts(), lastCause));
        }
    }

    private boolean sleep(long delayMs) {
        if (delayMs <= 0) {
            return true;
        }
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void transition(ReconnectState next) {
        stateRef.set(next);
        try {
            stateListener.accept(next);
        } catch (RuntimeException e) {
            log.warn("State listener threw on {}: {}", next.getClass().getSimpleName(), e.getMessage());
        }
        try {
            eventSink.accept(new ReconnectStateChangedEvent(connectionName, next));
        } catch (RuntimeException e) {
            log.warn("Event sink threw on ReconnectStateChangedEvent: {}", e.getMessage());
        }
    }

    private void publishLost(Throwable cause) {
        try {
            eventSink.accept(new ConnectionLostEvent(connectionName, cause));
        } catch (RuntimeException e) {
            log.warn("Event sink threw on ConnectionLostEvent: {}", e.getMessage());
        }
    }
}
