package com.botwithus.bot.api.isc;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Async pub/sub message bus for inter-script communication over named channels.
 *
 * <p><strong>On {@code sender}:</strong> the bus a running script receives is
 * bound to that script's identity, and the runtime stamps it on every outbound
 * message. The {@code sender} argument you pass is therefore advisory — what
 * subscribers observe is the script's registered name, not the string supplied
 * at the call site. Do not rely on being able to send under another name.</p>
 */
public interface MessageBus {

    /**
     * Subscribes a handler to receive messages on the given channel.
     *
     * @param channel the channel name to subscribe to
     * @param handler the callback invoked for each message on the channel
     */
    void subscribe(String channel, Consumer<ScriptMessage> handler);

    /**
     * Removes a previously registered handler from the given channel.
     *
     * @param channel the channel name
     * @param handler the handler to remove
     */
    void unsubscribe(String channel, Consumer<ScriptMessage> handler);

    /**
     * Publishes a message to all subscribers of the given channel.
     * Delivery is asynchronous; the caller never blocks.
     *
     * @param channel the channel to publish on
     * @param sender  identifier of the sending script
     * @param payload the message payload
     */
    void publish(String channel, String sender, Object payload);

    /**
     * Sends a request and waits for a response on the given channel.
     *
     * @param channel   the channel to send the request on
     * @param sender    identifier of the requesting script
     * @param payload   the request payload
     * @param timeoutMs maximum time to wait for a response in milliseconds
     * @return a future that completes with the response message
     */
    CompletableFuture<ScriptMessage> request(String channel, String sender, Object payload, long timeoutMs);

    /**
     * Sends a response to a previous request.
     *
     * @param requestId the correlation ID from the original request
     * @param channel   the channel to respond on
     * @param sender    identifier of the responding script
     * @param payload   the response payload
     */
    void respond(String requestId, String channel, String sender, Object payload);
}
