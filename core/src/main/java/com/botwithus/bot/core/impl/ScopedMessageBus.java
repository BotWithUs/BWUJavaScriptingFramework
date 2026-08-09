package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.isc.MessageBus;
import com.botwithus.bot.api.isc.ScriptMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Per-script view of a connection's {@link MessageBus} that remembers what the
 * script subscribed to, so the runtime can take it all back off again when the
 * script stops — the ISC counterpart of {@link ScopedEventBus}.
 *
 * <p>Without this, a stopped script keeps handling ISC traffic forever:
 * {@code MessageBusImpl} keeps handlers in a shared per-channel list and
 * {@link MessageBus#unsubscribe} needs the exact {@code Consumer} reference
 * back, which a script that subscribed with a lambda cannot produce. The
 * handler then outlives the script that registered it and can still drive the
 * game.</p>
 *
 * <p>This class needs one thing {@code ScopedEventBus} does not. The event bus
 * dispatches inline on the event-pump thread, so dropping the subscription is
 * enough; the message bus dispatches on a <em>fresh virtual thread per handler
 * per message</em>, so a delivery picked up microseconds before teardown can
 * still be waiting to run after it. Every handler is therefore registered
 * behind a gate that re-checks the release flag when the message reaches it,
 * not only at subscribe time.</p>
 *
 * <p>The gate is best-effort by construction: it rejects a delivery that has
 * not yet entered the script's handler, but a handler already executing runs to
 * completion. Narrowing the window is the whole of what is on offer — nothing
 * short of cancelling the thread could close it, and that is the escalation
 * path the watchdog owns.</p>
 *
 * <p>Release is one-way and covers the outbound direction too: after it, a
 * leftover thread belonging to the stopped script can no longer publish,
 * respond, or reach into the shared bus's subscriber lists. {@code onStop()}
 * runs before release, so a script can still say goodbye on its way out.</p>
 *
 * <p>Scoped only onto {@code BotScript} contexts. A {@code ManagementScript}
 * still receives the connection's raw bus, so this class does not contain one.</p>
 *
 * <p>Delegates every call to the shared bus while running — this only adds
 * bookkeeping, so scripts see identical behaviour until they stop.</p>
 */
public final class ScopedMessageBus implements MessageBus {

    private final MessageBus delegate;
    /**
     * One entry per live subscription. A list rather than a map keyed by handler
     * because the bus permits the same handler on several channels — and the same
     * handler twice on one channel — and each of those registrations needs its own
     * undo. Subscriptions per script are a handful, so the linear scan in
     * {@link #unsubscribe} is cheaper than the bookkeeping to avoid it.
     */
    private final List<Registration> registrations = new ArrayList<>();
    /** Guards {@link #registrations} and orders subscribe against {@link #unsubscribeAll}. */
    private final Object lock = new Object();
    private volatile boolean isReleased;

    /**
     * A live subscription: the {@code handler} the script passed in, and the
     * {@code gated} wrapper actually registered with the shared bus. Unsubscribing
     * has to hand back the wrapper, since that is what the delegate holds.
     */
    private record Registration(String channel, Consumer<ScriptMessage> handler,
                                Consumer<ScriptMessage> gated) {}

    public ScopedMessageBus(MessageBus delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void subscribe(String channel, Consumer<ScriptMessage> handler) {
        if (channel == null || handler == null) {
            return;
        }
        Consumer<ScriptMessage> gated = message -> {
            if (isReleased) {
                return;
            }
            handler.accept(message);
        };
        // Registering under the same lock unsubscribeAll holds gives the two a
        // total order: a subscribe either lands before release and is undone by
        // it, or sees the flag and never reaches the shared bus at all.
        synchronized (lock) {
            if (isReleased) {
                return;
            }
            registrations.add(new Registration(channel, handler, gated));
            delegate.subscribe(channel, gated);
        }
    }

    /**
     * Removes a subscription this scope made. Deliberately does nothing for a
     * handler it did not register: the shared bus holds gated wrappers, so
     * forwarding a raw handler reference could only ever hit another script's
     * subscription.
     */
    @Override
    public void unsubscribe(String channel, Consumer<ScriptMessage> handler) {
        if (isReleased || channel == null || handler == null) {
            return;
        }
        Consumer<ScriptMessage> registered = null;
        synchronized (lock) {
            for (int i = 0; i < registrations.size(); i++) {
                Registration candidate = registrations.get(i);
                if (channel.equals(candidate.channel()) && candidate.handler().equals(handler)) {
                    registered = candidate.gated();
                    registrations.remove(i);
                    break;
                }
            }
        }
        if (registered != null) {
            delegate.unsubscribe(channel, registered);
        }
    }

    @Override
    public void publish(String channel, String sender, Object payload) {
        if (isReleased) {
            return;
        }
        delegate.publish(channel, sender, payload);
    }

    @Override
    public CompletableFuture<ScriptMessage> request(String channel, String sender, Object payload,
                                                    long timeoutMs) {
        if (isReleased) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "ISC request from a stopped script on '" + channel + "'"));
        }
        return delegate.request(channel, sender, payload, timeoutMs);
    }

    @Override
    public void respond(String requestId, String channel, String sender, Object payload) {
        if (isReleased) {
            return;
        }
        delegate.respond(requestId, channel, sender, payload);
    }

    /**
     * Removes every subscription this script made and latches the scope closed,
     * so a delivery not yet handed to its handler — and any call from a thread
     * the script left behind — becomes a no-op. Called from the runner's cleanup,
     * and safe to call more than once.
     */
    public void unsubscribeAll() {
        List<Registration> live;
        synchronized (lock) {
            isReleased = true;
            live = List.copyOf(registrations);
            registrations.clear();
        }
        for (Registration registration : live) {
            delegate.unsubscribe(registration.channel(), registration.gated());
        }
    }
}
