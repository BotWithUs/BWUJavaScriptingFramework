package com.botwithus.bot.cli.gui.notify;

import com.botwithus.bot.cli.events.ClientKey;

/**
 * Where {@link ToastFeed} sends toasts. Both methods may be called from any
 * thread and return at once; the toasts change on the render thread.
 */
public interface ToastSink {

    /** Asks for {@code toast} to be shown. */
    void post(Toast toast);

    /** Takes down the toast about {@code client}'s connection, if one is showing. */
    void withdraw(ClientKey client);
}
