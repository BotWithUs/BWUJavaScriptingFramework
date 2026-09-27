package com.botwithus.bot.cli.gui.notify;

import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.gui.notify.Notification.Kind;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * A toast asked for, before it is on screen. {@link ToastFeed} builds these from
 * host events on the event thread; {@link NotificationOverlay} puts them on
 * screen on the render thread.
 *
 * @param kind      which event produced it
 * @param title     short title
 * @param message   one-line body
 * @param client    the client it is about, if any
 * @param lifetime  how long it stays; empty to stay until closed
 * @param canOpen   whether it may appear on its own. When {@code false} it only
 *                  updates a toast already showing for the same client, and is
 *                  dropped when there is none
 */
public record Toast(Kind kind, String title, String message, Optional<ClientKey> client,
                    Optional<Duration> lifetime, boolean canOpen) {

    public Toast {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(lifetime, "lifetime");
    }

    /** The same toast, allowed only to update one already showing for its client. */
    public Toast updateOnly() {
        return new Toast(kind, title, message, client, lifetime, false);
    }
}
