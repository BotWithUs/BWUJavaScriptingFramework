package com.botwithus.bot.core.alerts;

/**
 * Sends messages to one {@link AlertService}.
 *
 * <p>{@link #send} blocks for the whole exchange, retries included, and never
 * throws for a failed delivery: the failure is the {@link SendResult}. Call it off
 * any thread that must stay responsive.</p>
 */
public interface Notifier {

    /** The service this notifier sends to. */
    AlertService service();

    /** Sends {@code message} and reports how it went. */
    SendResult send(AlertMessage message);
}
