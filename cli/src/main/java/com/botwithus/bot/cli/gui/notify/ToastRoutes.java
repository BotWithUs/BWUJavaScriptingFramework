package com.botwithus.bot.cli.gui.notify;

import com.botwithus.bot.cli.events.ClientKey;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Runs a toast's action button. "Try again" retries the client's connection
 * where it stands, without tearing it down; "View log" opens the Logs tab scoped
 * to the client; "Details" on a toast that belongs to no client opens the Logs
 * tab for every client.
 *
 * @param retryNow    retries a client's connection at once
 * @param viewLog     opens the Logs tab scoped to a client
 * @param viewAllLogs opens the Logs tab for every client
 */
public record ToastRoutes(Consumer<ClientKey> retryNow, Consumer<ClientKey> viewLog, Runnable viewAllLogs)
        implements Consumer<Notification> {

    public ToastRoutes {
        Objects.requireNonNull(retryNow, "retryNow");
        Objects.requireNonNull(viewLog, "viewLog");
        Objects.requireNonNull(viewAllLogs, "viewAllLogs");
    }

    @Override
    public void accept(Notification toast) {
        switch (toast.kind().action()) {
            case TRY_AGAIN -> toast.client().ifPresent(retryNow);
            case VIEW_LOG, DETAILS -> toast.client().ifPresentOrElse(viewLog, viewAllLogs);
            case NONE -> { }
        }
    }
}
