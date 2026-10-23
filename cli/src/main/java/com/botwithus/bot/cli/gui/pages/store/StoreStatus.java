package com.botwithus.bot.cli.gui.pages.store;

import java.time.Instant;
import java.util.Optional;

/** What the Store can show right now, as the launcher's last answer decides it. */
public sealed interface StoreStatus {

    /** No answer from the launcher yet. */
    record Loading() implements StoreStatus {}

    /**
     * A catalogue to list.
     *
     * @param isStale      the launcher did not answer, so this is the last catalogue it gave
     * @param syncedAt     when this host first showed this catalogue
     * @param refreshError why the latest refresh failed, while an older good list is still
     *                     shown; empty when the latest refresh succeeded
     */
    record Ready(boolean isStale, Instant syncedAt, Optional<String> refreshError) implements StoreStatus {}

    /** Nothing to list, and a reason the user can act on. */
    record Unavailable(Notice notice) implements StoreStatus {}

    /** Why there is no catalogue. Each has its own words and its own thing to do about it. */
    enum Reason { LAUNCHER_NOT_RUNNING, SIGNED_OUT, NO_SUBSCRIPTION, FAILED }

    /** @param detail the launcher's own reason for {@link Reason#FAILED}; empty otherwise */
    record Notice(Reason reason, String detail) {}
}
