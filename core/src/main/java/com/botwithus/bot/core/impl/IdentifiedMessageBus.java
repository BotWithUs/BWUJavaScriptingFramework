package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.isc.MessageBus;
import com.botwithus.bot.api.isc.ScriptMessage;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Per-script {@link MessageBus} view that stamps every outbound message with
 * the runtime's own name for the sending script, ignoring the sender the
 * caller supplied.
 *
 * <p>{@code sender} is a plain parameter on {@link MessageBus}, so on the
 * shared bus any script could publish, request or respond under another
 * script's name — enough to spoof a coordination protocol between two scripts,
 * or to attribute a message to a script that never sent it. The runtime already
 * knows which script it is running, so it substitutes that identity here.</p>
 *
 * <p>The public signatures are deliberately unchanged: a script may still pass
 * a sender argument, it just no longer decides what subscribers observe.
 * Subscribe/unsubscribe and delivery semantics pass through untouched.</p>
 */
final class IdentifiedMessageBus implements MessageBus {

    private final MessageBus delegate;
    private final String identity;

    IdentifiedMessageBus(MessageBus delegate, String identity) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    @Override
    public void subscribe(String channel, Consumer<ScriptMessage> handler) {
        delegate.subscribe(channel, handler);
    }

    @Override
    public void unsubscribe(String channel, Consumer<ScriptMessage> handler) {
        delegate.unsubscribe(channel, handler);
    }

    @Override
    public void publish(String channel, String sender, Object payload) {
        delegate.publish(channel, identity, payload);
    }

    @Override
    public CompletableFuture<ScriptMessage> request(String channel, String sender, Object payload,
                                                    long timeoutMs) {
        return delegate.request(channel, identity, payload, timeoutMs);
    }

    @Override
    public void respond(String requestId, String channel, String sender, Object payload) {
        delegate.respond(requestId, channel, identity, payload);
    }
}
