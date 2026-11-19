package com.botwithus.bot.api.gameui;

/**
 * A registered {@code ui.event} listener. Closing it stops delivery; a script's
 * listeners are also dropped when the script stops.
 */
public interface UiSubscription extends AutoCloseable {

    /** Stops delivery. Safe to call more than once. */
    @Override
    void close();
}
