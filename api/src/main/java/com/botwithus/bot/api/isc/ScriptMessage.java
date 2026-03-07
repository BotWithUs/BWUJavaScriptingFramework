package com.botwithus.bot.api.isc;

/**
 * An immutable message sent over a named ISC channel.
 *
 * @param channel   the channel the message was published on
 * @param sender    identifier of the sending script
 * @param payload   the message payload
 * @param timestamp epoch millis when the message was created
 * @param requestId correlation ID for request/response patterns, or null for pub/sub
 */
public record ScriptMessage(String channel, String sender, Object payload, long timestamp, String requestId) {

    /** Backward-compatible constructor for pub/sub messages. */
    public ScriptMessage(String channel, String sender, Object payload, long timestamp) {
        this(channel, sender, payload, timestamp, null);
    }

    /** Returns true if this message is part of a request/response exchange. */
    public boolean isRequest() {
        return requestId != null;
    }
}
