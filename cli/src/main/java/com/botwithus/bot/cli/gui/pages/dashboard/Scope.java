package com.botwithus.bot.cli.gui.pages.dashboard;

import com.botwithus.bot.cli.events.ClientKey;

import java.util.Objects;
import java.util.Optional;

/**
 * Which clients the Dashboard shows: every client, or one client by the key the
 * host knows it by. One scope covers the whole page, the Logs tab and the Events
 * tab included, so "View log" on one client's toast shows that client everywhere
 * at once. A client keeps its key when its game restarts on a new pipe, so its
 * scope keeps showing what it did on the earlier pipe too.
 *
 * @param client the key of the one client shown, or empty for all of them
 */
public record Scope(Optional<ClientKey> client) {

    /** Every client, and the host-wide lines and events that belong to none. */
    public static final Scope ALL = new Scope(Optional.empty());

    public Scope {
        Objects.requireNonNull(client, "client");
    }

    /** Just the client under {@code key}. */
    public static Scope of(ClientKey key) {
        return new Scope(Optional.of(Objects.requireNonNull(key, "key")));
    }

    public boolean isAll() {
        return client.isEmpty();
    }

    /** Whether something about the client under {@code key} is shown. */
    public boolean includes(ClientKey key) {
        return client.map(c -> c.equals(key)).orElse(true);
    }
}
