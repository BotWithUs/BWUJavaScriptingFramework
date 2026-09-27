package com.botwithus.bot.cli.gui.pages.dashboard;

import java.util.Objects;
import java.util.Optional;

/**
 * Which clients the Dashboard shows: every client, or the one on a given pipe.
 * One scope covers the whole page, the Logs tab and the Events tab included, so
 * "View log" on one client's toast shows that client everywhere at once.
 *
 * @param client the pipe name of the one client shown, or empty for all of them
 */
public record Scope(Optional<String> client) {

    /** Every client, and the host-wide lines and events that belong to none. */
    public static final Scope ALL = new Scope(Optional.empty());

    public Scope {
        Objects.requireNonNull(client, "client");
    }

    /** Just the client on {@code pipe}. */
    public static Scope of(String pipe) {
        return new Scope(Optional.of(Objects.requireNonNull(pipe, "pipe")));
    }

    public boolean isAll() {
        return client.isEmpty();
    }

    /**
     * Whether something attributed to {@code pipe} is shown. Something attributed
     * to no client ({@code null}) is host-wide and shows only in {@link #ALL}.
     */
    public boolean includes(String pipe) {
        return client.map(c -> c.equals(pipe)).orElse(true);
    }
}
