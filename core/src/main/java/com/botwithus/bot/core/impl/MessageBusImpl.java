package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.isc.MessageBus;
import com.botwithus.bot.api.isc.ScriptMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class MessageBusImpl implements MessageBus {

    private static final Logger log = LoggerFactory.getLogger(MessageBusImpl.class);

    /**
     * Floor for {@link #request} timeouts, normalising a zero or negative timeout
     * to "expires as soon as possible" rather than leaving the behaviour of
     * {@link CompletableFuture#orTimeout} to the sign of the argument.
     */
    private static final long MIN_REQUEST_TIMEOUT_MS = 1L;
    /**
     * Ceiling for {@link #request} timeouts. {@code orTimeout(Long.MAX_VALUE, MILLISECONDS)}
     * never fires, so a request nobody answers pins its {@link #pendingRequests}
     * entry — and the payload it holds — for the life of the host.
     */
    private static final long MAX_REQUEST_TIMEOUT_MS = Duration.ofMinutes(5).toMillis();
    /**
     * Cap on handler invocations in flight across this bus. Dispatch starts one
     * virtual thread per subscriber per message with no back-pressure, so a script
     * publishing in a loop can create threads faster than handlers retire. Set far
     * above any legitimate fan-out: reaching it means something is flooding.
     */
    private static final int MAX_IN_FLIGHT_DISPATCHES = 1024;
    /** Cap on handlers one channel may hold, bounding the per-message fan-out. */
    private static final int MAX_HANDLERS_PER_CHANNEL = 256;
    /**
     * Cap on distinct channels. Channel names are caller-supplied and the map is
     * never pruned, so without this a script subscribing to a fresh name in a loop
     * grows it until the host runs out of heap.
     */
    private static final int MAX_CHANNELS = 1024;
    /** Cap on unanswered {@link #request} calls, bounding {@link #pendingRequests}. */
    private static final int MAX_PENDING_REQUESTS = 4096;

    public MessageBusImpl() {}

    private final Map<String, List<Consumer<ScriptMessage>>> subscribers = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<ScriptMessage>> pendingRequests = new ConcurrentHashMap<>();
    private final Semaphore dispatchPermits = new Semaphore(MAX_IN_FLIGHT_DISPATCHES);
    /** Latches the saturation warning so a flood logs once, not once per dropped message. */
    private final AtomicBoolean saturationLogged = new AtomicBoolean();

    /**
     * {@inheritDoc}
     *
     * <p>Refused, with a warning, once this bus is holding its cap of channels or
     * this channel its cap of handlers. Both are soft ceilings — the size check
     * and the add are not atomic, so concurrent callers can overshoot slightly —
     * which is all the bound needs to be to stop unbounded growth.</p>
     */
    @Override
    public void subscribe(String channel, Consumer<ScriptMessage> handler) {
        List<Consumer<ScriptMessage>> list = subscribers.get(channel);
        if (list == null) {
            if (subscribers.size() >= MAX_CHANNELS) {
                log.warn("Refusing ISC subscribe on '{}': {} channels already registered",
                        channel, MAX_CHANNELS);
                return;
            }
            list = subscribers.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>());
        }
        if (list.size() >= MAX_HANDLERS_PER_CHANNEL) {
            log.warn("Refusing ISC subscribe on '{}': {} handlers already registered",
                    channel, list.size());
            return;
        }
        list.add(handler);
    }

    @Override
    public void unsubscribe(String channel, Consumer<ScriptMessage> handler) {
        List<Consumer<ScriptMessage>> list = subscribers.get(channel);
        if (list != null) {
            list.remove(handler);
        }
    }

    @Override
    public void publish(String channel, String sender, Object payload) {
        List<Consumer<ScriptMessage>> list = subscribers.get(channel);
        if (list == null || list.isEmpty()) {
            return;
        }
        dispatch(list, new ScriptMessage(channel, sender, payload, System.currentTimeMillis()));
    }

    @Override
    public CompletableFuture<ScriptMessage> request(String channel, String sender, Object payload, long timeoutMs) {
        if (pendingRequests.size() >= MAX_PENDING_REQUESTS) {
            log.warn("Refusing ISC request on '{}' from {}: {} requests already pending",
                    channel, sender, MAX_PENDING_REQUESTS);
            return CompletableFuture.failedFuture(
                    new IllegalStateException("ISC request queue is full"));
        }
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<ScriptMessage> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);

        ScriptMessage message = new ScriptMessage(channel, sender, payload, System.currentTimeMillis(), requestId);
        List<Consumer<ScriptMessage>> list = subscribers.get(channel);
        if (list != null && !list.isEmpty()) {
            dispatch(list, message);
        }

        return future.orTimeout(clampRequestTimeout(timeoutMs), TimeUnit.MILLISECONDS)
                .whenComplete((result, error) -> pendingRequests.remove(requestId));
    }

    @Override
    public void respond(String requestId, String channel, String sender, Object payload) {
        CompletableFuture<ScriptMessage> future = pendingRequests.remove(requestId);
        if (future != null) {
            ScriptMessage response = new ScriptMessage(channel, sender, payload, System.currentTimeMillis(), requestId);
            future.complete(response);
        }
    }

    /**
     * Live handler count per channel — the ISC counterpart of
     * {@link EventBusImpl#getSubscriptionInfo()}, and the only way to see from
     * outside whether a stopped script's handlers were actually taken back off.
     * A channel that has been emptied reports {@code 0} rather than disappearing:
     * the bus never prunes channel keys.
     */
    public Map<String, Integer> getSubscriptionInfo() {
        return subscribers.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().size()));
    }

    /**
     * Confines {@code timeoutMs} to a range in which the timeout actually fires.
     * The upper bound is what stops an unanswered request leaking its pending
     * entry forever; see {@link #MAX_REQUEST_TIMEOUT_MS}.
     */
    static long clampRequestTimeout(long timeoutMs) {
        return Math.clamp(timeoutMs, MIN_REQUEST_TIMEOUT_MS, MAX_REQUEST_TIMEOUT_MS);
    }

    /**
     * Delivers {@code message} to each handler on its own virtual thread, skipping
     * any handler it cannot get a permit for. Delivery is therefore per-handler
     * best-effort under saturation: some subscribers may see a message that others
     * miss. Skipping rather than abandoning the loop keeps the drop from always
     * falling on the same tail of a channel's subscriber list.
     */
    private void dispatch(List<Consumer<ScriptMessage>> handlers, ScriptMessage message) {
        for (Consumer<ScriptMessage> handler : handlers) {
            if (!dispatchPermits.tryAcquire()) {
                warnSaturated(message);
                continue;
            }
            if (saturationLogged.get()) {
                saturationLogged.set(false);
            }
            startDelivery(handler, message);
        }
    }

    /**
     * Runs one handler on its own virtual thread and returns the permit however it
     * ends. A handler that throws is logged rather than left to the default
     * uncaught-exception handler, so one bad subscriber cannot make a publish look
     * like it never happened — and the release has to survive a failure to start
     * the thread at all, or the bus loses a permit for good.
     */
    private void startDelivery(Consumer<ScriptMessage> handler, ScriptMessage message) {
        try {
            Thread.startVirtualThread(() -> {
                try {
                    handler.accept(message);
                } catch (Exception e) {
                    log.debug("ISC handler error on '{}': {}", message.channel(), e.getMessage());
                } finally {
                    dispatchPermits.release();
                }
            });
        } catch (Throwable t) {
            dispatchPermits.release();
            throw t;
        }
    }

    private void warnSaturated(ScriptMessage message) {
        if (saturationLogged.compareAndSet(false, true)) {
            log.warn("ISC dispatch saturated at {} in flight; dropping delivery on '{}' from {}",
                    MAX_IN_FLIGHT_DISPATCHES, message.channel(), message.sender());
        }
    }
}
