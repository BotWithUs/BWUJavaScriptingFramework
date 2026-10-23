package com.botwithus.bot.cli.launcher;

import com.botwithus.bot.api.script.LauncherException;
import com.botwithus.bot.core.launcher.CloseDecision;
import com.botwithus.bot.core.launcher.CloseRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The launcher service's request that this host close (launcher ADR 0007,
 * section 6.3), between the thread that receives it and the render thread that
 * asks the user.
 *
 * <p>The request arrives on the service connection's reader thread and is
 * only parked here. The render thread shows it as a modal and calls
 * {@link #answer} with the user's choice; the answer goes back to the service
 * as {@code host.ack_close} on a worker thread, never the render thread. The
 * host closes only when the user chose {@link CloseDecision#CLOSING}: then
 * {@link #takeShutdownRequest()} turns true once, and the render thread runs
 * its normal shutdown. Nothing here quits on its own.</p>
 *
 * <p>A newer request replaces one the user has not answered.</p>
 */
public final class CloseRequestPrompt {

    /** Sends the user's answer to the service. */
    @FunctionalInterface
    public interface Acknowledger {
        void ack(long requestId, CloseDecision decision);
    }

    private static final Logger log = LoggerFactory.getLogger(CloseRequestPrompt.class);

    private final Acknowledger acknowledger;
    private final Executor worker;
    private final Optional<CloseDecision> autoAnswer;
    private final AtomicReference<CloseRequest> pending = new AtomicReference<>();
    private final AtomicReference<CloseRequest> lastShown = new AtomicReference<>();
    private final AtomicBoolean isShutdownRequested = new AtomicBoolean();

    /**
     * @param acknowledger sends {@code host.ack_close}
     * @param worker       runs the acknowledgement off the render thread
     * @param autoAnswer   a development-only answer the modal gives by itself,
     *                     for tests that must not be clicked by a person
     */
    public CloseRequestPrompt(Acknowledger acknowledger, Executor worker, Optional<CloseDecision> autoAnswer) {
        this.acknowledger = Objects.requireNonNull(acknowledger, "acknowledger");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.autoAnswer = Objects.requireNonNull(autoAnswer, "autoAnswer");
    }

    /** Parks a request; called on the service reader thread, so it returns at once. */
    public void offer(CloseRequest request) {
        log.info("The launcher asked this host to close (request {}, {} host(s) blocking a data update)",
                request.requestId(), request.hostsBlocking());
        pending.set(request);
    }

    /** @return the request to show now, if any */
    public Optional<CloseRequest> current() {
        return Optional.ofNullable(pending.get());
    }

    /** Records that {@code request} is on screen; logs the first time each one is. */
    public void markShown(CloseRequest request) {
        if (lastShown.getAndSet(request) != request) {
            log.info("Close request {} shown", request.requestId());
        }
    }

    /** @return the development-only answer to give without a click, if one is configured */
    public Optional<CloseDecision> autoAnswer() {
        return autoAnswer;
    }

    /**
     * The user's answer to {@code request}. Ignored if a newer request has
     * replaced it since it was drawn.
     */
    public void answer(CloseRequest request, CloseDecision decision) {
        if (!pending.compareAndSet(request, null)) {
            return;
        }
        log.info("Close request {} answered {}", request.requestId(), decision.wire());
        worker.execute(() -> acknowledge(request, decision));
    }

    /** @return {@code true} once after the user chose to close the host */
    public boolean takeShutdownRequest() {
        return isShutdownRequested.compareAndSet(true, false);
    }

    private void acknowledge(CloseRequest request, CloseDecision decision) {
        try {
            acknowledger.ack(request.requestId(), decision);
        } catch (LauncherException e) {
            log.warn("Could not tell the launcher the answer to close request {}: {}", request.requestId(),
                    e.getMessage());
        }
        if (decision == CloseDecision.CLOSING) {
            isShutdownRequested.set(true);
        }
    }
}
