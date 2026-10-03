package com.botwithus.bot.core.launcher;

import com.botwithus.bot.api.script.LauncherEvent;
import com.botwithus.bot.api.script.LauncherException;
import com.botwithus.bot.core.launcher.FrameChannel.ChannelClosedException;
import org.msgpack.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * This process's one connection to the launcher service's automation pipe:
 * its host registration (launcher ADR 0007, section 6.1).
 *
 * <p>Opened once by the composition root, before any script runs, and held for
 * the life of the process. Connecting is registering: the {@code hello} names
 * this process, and while the connection is up a data update waits for this
 * host. After a broken connection or a service restart it reconnects with
 * back-off (1, 2, 4, 8, then every 15 s, forever), says {@code hello} again and
 * so registers again. <b>It never starts the service.</b></p>
 *
 * <p>Between connections every call fails with {@code service_stopped} when the
 * user stopped the service from the tray, and {@code service_unavailable}
 * otherwise. A call the service does not answer within the call timeout (40 s)
 * closes the connection, which then reconnects, and fails with
 * {@code service_unavailable}.</p>
 *
 * <p><b>Keepalive.</b> A broken pipe is invisible to a reader that only peeks
 * (see {@code RpcClient}), so when nothing has crossed the connection for the
 * keepalive interval (5 s) this sends {@code service.status}. A failed write or
 * a missing answer is how a dead service is noticed. The launcher ADR is silent
 * on this; it is a host-side requirement.</p>
 */
public final class LauncherService implements AutoCloseable {

    /**
     * How the connection paces itself.
     *
     * @param backoff     the delays before the first reconnect attempts
     * @param steady      the delay between attempts after those
     * @param keepalive   idle time before a {@code service.status} probe
     * @param callTimeout how long a call waits for its answer
     * @param readPoll    how long the reader sleeps when nothing is waiting
     */
    public record Timings(List<Duration> backoff, Duration steady, Duration keepalive, Duration callTimeout,
                          Duration readPoll) {

        /** The production pacing (ADR 6.1, and the lead's 40 s call timeout). */
        public static final Timings DEFAULT = new Timings(
                List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8)),
                Duration.ofSeconds(15), Duration.ofSeconds(5), Duration.ofSeconds(40), Duration.ofMillis(10));

        public Timings {
            backoff = List.copyOf(backoff);
            Objects.requireNonNull(steady, "steady");
            Objects.requireNonNull(keepalive, "keepalive");
            Objects.requireNonNull(callTimeout, "callTimeout");
            Objects.requireNonNull(readPoll, "readPoll");
        }

        /** @return the delay before reconnect attempt {@code attempt} (0-based) */
        Duration delayBefore(int attempt) {
            return attempt < backoff.size() ? backoff.get(attempt) : steady;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(LauncherService.class);

    private final FrameChannel.Opener opener;
    private final HelloParams hello;
    private final BooleanSupplier isUserStopped;
    private final Timings timings;
    private final Consumer<CloseRequest> closeRequests;
    private final List<Consumer<LauncherEvent>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong registrations = new AtomicLong();
    private volatile ServiceSession session;
    private volatile boolean isRunning;
    private volatile Thread connector;

    /**
     * @param opener        opens the automation pipe
     * @param hello         what this host says about itself
     * @param isUserStopped whether the user-stopped flag exists now
     * @param timings       pacing
     * @param closeRequests receives {@code host.close_requested}; called on the
     *                      reader thread, so it must hand the request off and return
     */
    public LauncherService(FrameChannel.Opener opener, HelloParams hello, BooleanSupplier isUserStopped,
                           Timings timings, Consumer<CloseRequest> closeRequests) {
        this.opener = Objects.requireNonNull(opener, "opener");
        this.hello = Objects.requireNonNull(hello, "hello");
        this.isUserStopped = Objects.requireNonNull(isUserStopped, "isUserStopped");
        this.timings = Objects.requireNonNull(timings, "timings");
        this.closeRequests = Objects.requireNonNull(closeRequests, "closeRequests");
    }

    /** Starts connecting in the background. Returns at once. */
    public synchronized void start() {
        if (isRunning) {
            return;
        }
        isRunning = true;
        connector = Thread.ofVirtual().name("launcher-service").start(this::connectLoop);
    }

    /**
     * Calls a method and returns its body.
     *
     * @throws LauncherException the service's error, or {@code service_unavailable} /
     *                           {@code service_stopped} when no service answered
     */
    Value call(String method, Value body) {
        ServiceSession current = session;
        if (current == null) {
            throw notConnected(null);
        }
        Envelope response;
        try {
            response = current.call(method, body, timings.callTimeout());
        } catch (ChannelClosedException e) {
            throw notConnected(e);
        }
        if (!response.isOk()) {
            WireError error = response.error().orElseGet(() -> new WireError(WireError.UNKNOWN_CODE,
                    "a failed response with no error", false, OptionalLong.empty(), Optional.empty()));
            throw error.toException(method);
        }
        return response.body();
    }

    /**
     * Answers a close request ({@code host.ack_close}).
     *
     * @throws LauncherException as {@link #call}
     */
    public void ackClose(long requestId, CloseDecision decision) {
        call(LauncherProtocol.METHOD_HOST_ACK_CLOSE, LauncherRequests.ackClose(requestId, decision));
    }

    /**
     * Adds a listener for events. It is called on the reader thread and must
     * hand the event off rather than block.
     *
     * @return removes the listener
     */
    public Runnable addListener(Consumer<LauncherEvent> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        return () -> listeners.remove(listener);
    }

    /** @return whether a registered connection is up now */
    public boolean isConnected() {
        ServiceSession current = session;
        return current != null && !current.isClosed();
    }

    /** @return how many times this host has registered, the first time included */
    public long registrationCount() {
        return registrations.get();
    }

    @Override
    public void close() {
        isRunning = false;
        Thread running = connector;
        if (running != null) {
            running.interrupt();
        }
        ServiceSession current = session;
        if (current != null) {
            current.close();
        }
    }

    private void connectLoop() {
        int attempt = 0;
        boolean wasConnected = false;
        try {
            while (isRunning) {
                ServiceSession connected = tryConnect();
                if (connected == null) {
                    Thread.sleep(timings.delayBefore(attempt++));
                    continue;
                }
                attempt = 0;
                if (wasConnected) {
                    emit(new LauncherEvent.ServiceRestored());
                }
                wasConnected = true;
                superviseUntilLost(connected);
                session = null;
                if (isRunning) {
                    emit(new LauncherEvent.ServiceLost(lostCode()));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** @return a registered session, or {@code null} when this attempt failed */
    private ServiceSession tryConnect() {
        FrameChannel channel;
        try {
            channel = opener.open();
        } catch (ChannelClosedException e) {
            log.debug("Launcher service not reachable: {}", e.getMessage());
            return null;
        }
        ServiceSession candidate = new ServiceSession(channel, timings.readPoll(), new SessionListener());
        candidate.start();
        try {
            register(candidate);
        } catch (ChannelClosedException | LauncherException e) {
            log.warn("Launcher service refused this host's registration: {}", e.getMessage());
            candidate.close();
            return null;
        }
        session = candidate;
        long count = registrations.incrementAndGet();
        log.info("Registered with the launcher service (registration {})", count);
        return candidate;
    }

    /** {@code hello}, then a subscription to every automation topic. */
    private void register(ServiceSession candidate) {
        Envelope reply = candidate.call(LauncherProtocol.METHOD_HELLO, LauncherRequests.hello(hello),
                timings.callTimeout());
        if (!reply.isOk()) {
            throw reply.error().map(e -> e.toException(LauncherProtocol.METHOD_HELLO))
                    .orElseGet(() -> new LauncherException(WireError.UNKNOWN_CODE, "hello failed"));
        }
        HelloReply.check(reply.body());
        subscribe(candidate);
    }

    private void subscribe(ServiceSession target) {
        Envelope reply = target.call(LauncherProtocol.METHOD_EVENTS_SUBSCRIBE,
                LauncherRequests.subscribe(LauncherProtocol.AUTOMATION_TOPICS), timings.callTimeout());
        if (!reply.isOk()) {
            throw reply.error().map(e -> e.toException(LauncherProtocol.METHOD_EVENTS_SUBSCRIBE))
                    .orElseGet(() -> new LauncherException(WireError.UNKNOWN_CODE, "events.subscribe failed"));
        }
    }

    /** Probes an idle connection until it closes. */
    private void superviseUntilLost(ServiceSession connected) throws InterruptedException {
        while (isRunning && !connected.awaitClosed(timings.keepalive())) {
            if (connected.idleFor().compareTo(timings.keepalive()) >= 0) {
                probe(connected);
            }
        }
        connected.close();
    }

    private void probe(ServiceSession connected) {
        try {
            connected.call(LauncherProtocol.METHOD_SERVICE_STATUS, Envelope.emptyBody(), timings.callTimeout());
        } catch (ChannelClosedException e) {
            log.info("Lost the launcher service: {}", e.getMessage());
        }
    }

    private String lostCode() {
        return isUserStopped.getAsBoolean() ? LauncherException.SERVICE_STOPPED
                : LauncherException.SERVICE_UNAVAILABLE;
    }

    private LauncherException notConnected(Throwable cause) {
        String code = lostCode();
        String message = code.equals(LauncherException.SERVICE_STOPPED)
                ? "The BotWithUs service was stopped from the tray."
                : "The BotWithUs service is not reachable.";
        return cause == null ? new LauncherException(code, message)
                : new LauncherException(code, message + " " + cause.getMessage(), cause);
    }

    private void emit(LauncherEvent event) {
        for (Consumer<LauncherEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                log.warn("A launcher event listener failed on {}", event, e);
            }
        }
    }

    /** A sequence gap means missed events: report it, then refresh the subscription. */
    private void onEventsMissed(ServiceSession source, long missed) {
        log.warn("Missed {} launcher service event(s); subscribing again", missed);
        emit(new LauncherEvent.EventsDropped(missed));
        Thread.ofVirtual().name("launcher-resubscribe").start(() -> {
            try {
                subscribe(source);
            } catch (ChannelClosedException | LauncherException e) {
                log.warn("Could not subscribe again after a gap: {}", e.getMessage());
            }
        });
    }

    private final class SessionListener implements ServiceSession.Listener {

        @Override
        public void onEvent(ServiceSession source, Envelope event, long missed) {
            if (missed > 0) {
                onEventsMissed(source, missed);
            }
            if (event.method().equals(LauncherProtocol.EVENT_HOST_CLOSE_REQUESTED)) {
                deliverCloseRequest(LauncherDecoder.closeRequest(event.body()));
                return;
            }
            LauncherDecoder.event(event.method(), event.body()).ifPresent(LauncherService.this::emit);
        }

        @Override
        public void onClosed(ServiceSession closedSession, Throwable cause) {
            log.debug("Launcher service connection closed: {}", cause.getMessage());
        }

        private void deliverCloseRequest(CloseRequest request) {
            try {
                closeRequests.accept(request);
            } catch (RuntimeException e) {
                log.warn("The close-request handler failed on {}", request, e);
            }
        }
    }
}
