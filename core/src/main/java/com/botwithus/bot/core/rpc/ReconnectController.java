package com.botwithus.bot.core.rpc;

import com.botwithus.bot.api.event.ConnectionLostEvent;
import com.botwithus.bot.api.event.GameEvent;
import com.botwithus.bot.api.event.ReconnectStateChangedEvent;
import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.pipe.PipeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Wraps an {@link RpcClient} + {@link PipeClient} pair and recovers transient
 * pipe drops by reopening the transport and restarting the reader loop. The
 * controller is composed over the two clients via constructor injection — it
 * does not subclass either.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #arm} wires {@code rpc.setDisconnectHandler} to this controller.</li>
 *   <li>On disconnect, a virtual thread runs one <em>recovery</em>: it
 *       transitions the state machine ({@link ReconnectState}) and retries with
 *       the backoff of the {@link ReconnectPolicy} it read when it started. The
 *       policy is re-read for every recovery, so a settings change applies to
 *       the next one without disturbing the one in progress.</li>
 *   <li>A recovery ends in {@code Connected}, or in {@code GivingUp} when the
 *       budget runs out, the client is gone, or {@link #stopRetrying} is called.
 *       {@link #retryNow} cuts a back-off short, or starts a fresh recovery
 *       after {@code GivingUp}.</li>
 *   <li>{@link #close} stops the controller for good, silently: an in-flight
 *       retry observes it at its next step and exits without further
 *       transitions.</li>
 * </ol>
 *
 * <p>Both callbacks ({@code stateListener} and {@code eventSink}) are
 * constructor-injected as {@code Consumer<...>} parameters — no inheritance,
 * no statics. Only the recovery thread calls them, one transition at a time,
 * which is what keeps them in order; and never with a lock held, because the
 * event sink reaches script code. {@link #retryNow} and {@link #stopRetrying}
 * only signal that thread, so the render thread can call them without ever
 * running, or waiting on, a listener.</p>
 */
public final class ReconnectController implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReconnectController.class);

    /** What {@link #retryNow()} did. */
    public enum RetryOutcome {
        /** A recovery was in progress; its next attempt runs now instead of after the back-off. */
        WOKEN,
        /** Recovery had given up or been stopped; a fresh recovery has started. */
        RESTARTED,
        /** The connection is up; there is nothing to retry. */
        NOT_NEEDED,
        /**
         * The client's game process has exited, so its pipe can never return and
         * no retry was started. Forget the client instead.
         */
        CLIENT_GONE,
        /** The controller was closed. */
        CLOSED
    }

    /**
     * Functional seam for the actual reconnect operation. Production wires
     * this to {@code rpc::reconnect}; tests substitute a fake that fails N
     * times before succeeding.
     *
     * <p>Takes the pipe name resolved for this attempt rather than closing
     * over one captured at construction — see {@link PipeResolver}.</p>
     */
    @FunctionalInterface
    public interface Reconnector {
        void reconnect(String pipeName) throws PipeException;
    }

    /**
     * Functional seam deciding, per attempt, which pipe to reconnect to and
     * whether recovery is still possible at all. Production wires this to
     * {@link SamePidPipeResolver}; tests substitute a scripted sequence.
     */
    @FunctionalInterface
    public interface PipeResolver {
        PipeResolution resolve(int attempt);
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

    /** How one attempt of a recovery ended. */
    private sealed interface Step {
        /** The pipe is back. */
        record Recovered() implements Step { }

        /** This attempt failed; the recovery may try again. */
        record Failed(Throwable cause) implements Step { }

        /** The resolver says the pipe is gone; the recovery must end. */
        record Gone(PipeResolution.Gone verdict) implements Step { }
    }

    /** What the recovery thread must do next, having ended a recovery. */
    private sealed interface Next {
        /** A retry was asked for while the recovery was ending: recover again. */
        record Retry(Throwable cause) implements Next { }

        /** The pipe came back and dropped again before the recovery ended: report it and recover. */
        record Drop(Throwable cause) implements Next { }
    }

    /** Where a recovery stands when it checks for a stop or a close. */
    private enum Checkpoint { GO, STOPPED, CLOSED }

    private final DisconnectArmer disconnectArmer;
    private final Reconnector reconnector;
    private final PipeResolver pipeResolver;
    private final String connectionName;
    private final Supplier<ReconnectPolicy> policySource;
    private final Consumer<ReconnectState> stateListener;
    private final Consumer<GameEvent> eventSink;
    private final AtomicReference<ReconnectState> stateRef =
            new AtomicReference<>(new ReconnectState.Connected(System.currentTimeMillis()));
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /** Guards the recovery flags below. Never held while a listener runs. */
    private final Object lock = new Object();
    /** Guarded by {@link #lock}: a recovery thread is running and owns every transition. */
    private boolean recovering;
    /** Guarded by {@link #lock}: cut the current or next back-off short. */
    private boolean wakeRequested;
    /** Guarded by {@link #lock}: end the recovery at its next step with {@code GivingUp}. */
    private boolean stopRequested;
    /** Guarded by {@link #lock}: a drop reported while a recovery was already running. */
    private Throwable pendingDrop;
    /** Guarded by {@link #lock}: attempts the current recovery has finished. */
    private int attemptsMade;
    /** The policy of the current or most recent recovery; {@code null} before the first. */
    private volatile ReconnectPolicy recoveryPolicy;
    private volatile boolean clientGone;
    private volatile Thread recoveryThread;

    /**
     * Production wiring: wraps the supplied {@link RpcClient} and
     * {@link PipeClient}. The {@code pipe} reference is documented in the
     * signature per the plan's seam (composition over both) but the recovery
     * loop drives reconnect exclusively through {@link RpcClient#reconnect}
     * so the underlying lock + reader restart stay co-located there.
     *
     * @param policySource read once at the start of each recovery
     */
    public ReconnectController(RpcClient rpc, PipeClient pipe, String pipeName,
                               String connectionName, Supplier<ReconnectPolicy> policySource,
                               Consumer<ReconnectState> stateListener,
                               Consumer<GameEvent> eventSink) {
        this(rpc::setDisconnectHandler, rpc::reconnect,
                new SamePidPipeResolver(pipeName)::resolve,
                connectionName, policySource, stateListener, eventSink);
    }

    /**
     * Function-seam constructor for tests and bespoke wiring, with a policy
     * that never changes.
     */
    public ReconnectController(DisconnectArmer disconnectArmer, Reconnector reconnector,
                               PipeResolver pipeResolver,
                               String connectionName, ReconnectPolicy policy,
                               Consumer<ReconnectState> stateListener,
                               Consumer<GameEvent> eventSink) {
        this(disconnectArmer, reconnector, pipeResolver, connectionName, () -> policy,
                stateListener, eventSink);
    }

    /**
     * Function-seam constructor for tests and bespoke wiring.
     *
     * @param policySource read once at the start of each recovery
     */
    public ReconnectController(DisconnectArmer disconnectArmer, Reconnector reconnector,
                               PipeResolver pipeResolver,
                               String connectionName, Supplier<ReconnectPolicy> policySource,
                               Consumer<ReconnectState> stateListener,
                               Consumer<GameEvent> eventSink) {
        this.disconnectArmer = disconnectArmer;
        this.reconnector = reconnector;
        this.pipeResolver = pipeResolver;
        this.connectionName = connectionName;
        this.policySource = policySource;
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
        if (claimRecoveryForDrop(cause)) {
            runRecoveries(new Next.Drop(cause));
        }
    }

    public ReconnectState currentState() {
        return stateRef.get();
    }

    /**
     * The attempt budget of the current or most recent recovery, or of the
     * next one if none has run yet. Empty when the policy is unlimited.
     */
    public OptionalInt maxAttempts() {
        ReconnectPolicy policy = recoveryPolicy;
        return (policy != null ? policy : policySource.get()).attemptLimit();
    }

    /**
     * Whether the last recovery ended because the client's game process
     * exited. Its pipe can never return, so a retry cannot succeed: offer to
     * forget the client instead. Cleared when a recovery starts.
     */
    public boolean isClientGone() {
        return clientGone;
    }

    /**
     * Retries at once: cuts the back-off of a recovery in progress short, or
     * starts a fresh recovery, counting attempts from one again, after
     * {@code GivingUp}. Never blocks and never runs a listener; a fresh
     * recovery runs on its own virtual thread.
     */
    public RetryOutcome retryNow() {
        synchronized (lock) {
            if (closed.get()) {
                return RetryOutcome.CLOSED;
            }
            if (recovering) {
                stopRequested = false;
                wakeRequested = true;
                lock.notifyAll();
                return RetryOutcome.WOKEN;
            }
            return switch (stateRef.get()) {
                case ReconnectState.Connected ignored -> RetryOutcome.NOT_NEEDED;
                case ReconnectState.GivingUp ignored when clientGone -> RetryOutcome.CLIENT_GONE;
                case ReconnectState.GivingUp gaveUp -> restart(gaveUp.lastCause());
                case ReconnectState.Disconnected dropped -> restart(dropped.cause());
                case ReconnectState.Reconnecting ignored ->
                        restart(new PipeException("retry requested for '" + connectionName + "'"));
            };
        }
    }

    /**
     * Stops the recovery in progress: it publishes {@code GivingUp} at its next
     * step, which is at once if it is waiting out a back-off, or when the attempt
     * in flight returns. If that attempt succeeds, the recovery ends in
     * {@code Connected} instead, because the pipe really is back.
     * {@link #retryNow} undoes a stop. Never blocks and never runs a listener.
     *
     * @return {@code false} if no recovery was in progress to stop
     */
    public boolean stopRetrying() {
        synchronized (lock) {
            if (closed.get() || !recovering || stopRequested) {
                return false;
            }
            stopRequested = true;
            wakeRequested = false;
            lock.notifyAll();
            return true;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed.set(true);
            lock.notifyAll();
        }
        Thread t = this.recoveryThread;
        if (t != null) {
            t.interrupt();
        }
    }

    private void onDisconnect(Throwable cause) {
        if (claimRecoveryForDrop(cause)) {
            startRecoveryThread(new Next.Drop(cause));
        }
    }

    /**
     * Claims a new recovery for a drop. A drop reported while a recovery is
     * running can only follow a reconnect that succeeded, so it is kept for
     * that recovery to handle once it has published {@code Connected}.
     *
     * @return whether the caller should run the new recovery
     */
    private boolean claimRecoveryForDrop(Throwable cause) {
        synchronized (lock) {
            if (closed.get()) {
                return false;
            }
            if (recovering) {
                pendingDrop = cause;
                return false;
            }
            claimRecovery();
            return true;
        }
    }

    /** Must hold {@link #lock}. Resets the flags and reads the policy for a new recovery. */
    private void claimRecovery() {
        recovering = true;
        wakeRequested = false;
        stopRequested = false;
        pendingDrop = null;
        attemptsMade = 0;
        clientGone = false;
        recoveryPolicy = policySource.get();
    }

    /** Must hold {@link #lock}. */
    private RetryOutcome restart(Throwable lastCause) {
        claimRecovery();
        log.info("Retrying '{}' on request", connectionName);
        startRecoveryThread(new Next.Retry(lastCause));
        return RetryOutcome.RESTARTED;
    }

    private void startRecoveryThread(Next first) {
        this.recoveryThread = Thread.ofVirtual()
                .name("reconnect-" + connectionName)
                .start(() -> runRecoveries(first));
    }

    /** The recovery thread's body: one recovery, then any that was asked for while it ended. */
    private void runRecoveries(Next first) {
        Optional<Next> next = Optional.of(first);
        while (next.isPresent()) {
            next = switch (next.get()) {
                case Next.Retry retry -> recover(retry.cause());
                case Next.Drop drop -> {
                    publishLost(drop.cause());
                    transition(new ReconnectState.Disconnected(System.currentTimeMillis(), drop.cause()));
                    yield recover(drop.cause());
                }
            };
        }
    }

    private Optional<Next> recover(Throwable initialCause) {
        ReconnectPolicy policy = recoveryPolicy;
        Throwable lastCause = initialCause;
        for (int attempt = 1; policy.allowsAttempt(attempt); attempt++) {
            long delay = policy.delayForAttempt(attempt);
            Checkpoint checkpoint = checkpoint();
            if (checkpoint == Checkpoint.GO) {
                transition(new ReconnectState.Reconnecting(System.currentTimeMillis(), attempt, delay));
                checkpoint = awaitBackoff(delay);
            }
            if (checkpoint != Checkpoint.GO) {
                return halt(checkpoint, lastCause);
            }
            switch (attemptOnce(attempt)) {
                case Step.Recovered ignored -> {
                    return afterRecovered();
                }
                case Step.Failed failed -> lastCause = failed.cause();
                case Step.Gone gone -> {
                    return giveUp(new PipeException(gone.verdict().detail()),
                            gone.verdict().reason() == PipeResolution.Gone.Reason.PROCESS_EXITED);
                }
            }
        }
        return giveUp(lastCause, false);
    }

    private Step attemptOnce(int attempt) {
        Step step = switch (pipeResolver.resolve(attempt)) {
            case PipeResolution.Found found -> tryReconnect(found.pipeName(), attempt);
            case PipeResolution.NotYet notYet -> {
                log.debug("Reconnect attempt {} for '{}': {}", attempt, connectionName, notYet.detail());
                yield new Step.Failed(new PipeException(notYet.detail()));
            }
            case PipeResolution.Gone gone -> new Step.Gone(gone);
        };
        synchronized (lock) {
            attemptsMade = attempt;
        }
        return step;
    }

    private Step tryReconnect(String pipeName, int attempt) {
        try {
            reconnector.reconnect(pipeName);
        } catch (PipeException e) {
            log.warn("Reconnect attempt {} for '{}' failed: {}",
                    attempt, connectionName, e.getMessage());
            return new Step.Failed(e);
        }
        log.info("Reconnect succeeded for '{}' on attempt {} (pipe '{}')",
                connectionName, attempt, pipeName);
        return new Step.Recovered();
    }

    /**
     * Publishes {@code Connected}, even if a stop was asked for meanwhile: the
     * pipe is back whatever the user wanted. A drop that arrived after the
     * reconnect is recovered next rather than lost.
     */
    private Optional<Next> afterRecovered() {
        if (!closed.get()) {
            transition(new ReconnectState.Connected(System.currentTimeMillis()));
        }
        synchronized (lock) {
            Throwable drop = pendingDrop;
            if (drop != null && !closed.get()) {
                claimRecovery();
                return Optional.of(new Next.Drop(drop));
            }
            recovering = false;
            return Optional.empty();
        }
    }

    /**
     * Terminal stop. Logged at ERROR, not WARN: the connection is dead, no
     * further attempt will be made, and the user has to act. A silent give-up
     * here is how a scripter ends up staring at a session that will never come
     * back with nothing in the log naming why.
     */
    private Optional<Next> giveUp(Throwable cause, boolean isProcessGone) {
        if (closed.get()) {
            return endRecovery(cause, false);
        }
        int attempts = attemptsSoFar();
        log.error("Giving up reconnecting '{}' after {} attempt(s): {}. "
                        + "The connection will not recover on its own — reconnect to the "
                        + "running game to rebuild it.",
                connectionName, attempts, cause.getMessage());
        clientGone = isProcessGone;
        transition(new ReconnectState.GivingUp(System.currentTimeMillis(), attempts, cause));
        return endRecovery(cause, !isProcessGone);
    }

    /** Ends a recovery that was stopped (publishing {@code GivingUp}) or closed (publishing nothing). */
    private Optional<Next> halt(Checkpoint checkpoint, Throwable lastCause) {
        if (checkpoint == Checkpoint.STOPPED) {
            int attempts = attemptsSoFar();
            log.info("Stopped reconnecting '{}' on request after {} attempt(s)", connectionName, attempts);
            transition(new ReconnectState.GivingUp(System.currentTimeMillis(), attempts,
                    new CancellationException("Stopped retrying on request")));
        }
        return endRecovery(lastCause, true);
    }

    /**
     * Ends the recovery, unless a {@link #retryNow} arrived while it was ending:
     * that retry is honoured rather than lost, by recovering again. A drop is
     * moot once the recovery has failed, so it is dropped.
     */
    private Optional<Next> endRecovery(Throwable cause, boolean isRetryable) {
        synchronized (lock) {
            boolean isRetryPending = wakeRequested && !stopRequested;
            if (isRetryable && isRetryPending && !closed.get()) {
                claimRecovery();
                return Optional.of(new Next.Retry(cause));
            }
            recovering = false;
            pendingDrop = null;
            return Optional.empty();
        }
    }

    private int attemptsSoFar() {
        synchronized (lock) {
            return attemptsMade;
        }
    }

    private Checkpoint checkpoint() {
        synchronized (lock) {
            return checkpointLocked();
        }
    }

    /** Must hold {@link #lock}. */
    private Checkpoint checkpointLocked() {
        if (closed.get()) {
            return Checkpoint.CLOSED;
        }
        return stopRequested ? Checkpoint.STOPPED : Checkpoint.GO;
    }

    /** Waits out {@code delayMs} unless {@link #retryNow}, a stop or a close cuts it short. */
    private Checkpoint awaitBackoff(long delayMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs);
        synchronized (lock) {
            try {
                long remaining = deadline - System.nanoTime();
                while (!wakeRequested && checkpointLocked() == Checkpoint.GO && remaining > 0) {
                    TimeUnit.NANOSECONDS.timedWait(lock, remaining);
                    remaining = deadline - System.nanoTime();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Checkpoint.CLOSED;
            }
            wakeRequested = false;
            return checkpointLocked();
        }
    }

    /** Called only by the thread running the recovery, and never with {@link #lock} held. */
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
