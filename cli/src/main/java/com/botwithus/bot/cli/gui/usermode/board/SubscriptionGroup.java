package com.botwithus.bot.cli.gui.usermode.board;

import java.util.List;

/**
 * What the picker's "Your subscriptions" group shows. Every variant but
 * {@link Listed} is a one-line note or nothing at all, never an error screen:
 * the local scripts below it stay usable whatever happened here.
 */
public sealed interface SubscriptionGroup {

    /**
     * The launcher is too old to report subscriptions, so the group is not drawn.
     * Showing an empty group would claim the account has none.
     */
    record Hidden() implements SubscriptionGroup {}

    /** The first catalogue fetch has not finished. */
    record Pending() implements SubscriptionGroup {}

    /**
     * The catalogue could not be read; {@code detail} is the launcher's message
     * for {@link Reason#FAILED}, and blank otherwise.
     */
    record Unavailable(Reason reason, String detail) implements SubscriptionGroup {}

    /**
     * The subscribed scripts that run on this host, possibly none.
     *
     * @param stale the list is the launcher's last answer on disk, because it did not answer now
     */
    record Listed(List<SubscriptionEntry> entries, boolean stale) implements SubscriptionGroup {

        public Listed {
            entries = List.copyOf(entries);
        }
    }

    /** Why the catalogue is unavailable; the same cases the Scripts Store panel tells apart. */
    enum Reason {
        LAUNCHER_NOT_RUNNING,
        NOT_SIGNED_IN,
        NO_ACCESS,
        FAILED
    }

    /** The rows to list; empty for every variant but {@link Listed}. */
    default List<SubscriptionEntry> entries() {
        return switch (this) {
            case Listed listed -> listed.entries;
            case Hidden ignored -> List.of();
            case Pending ignored -> List.of();
            case Unavailable ignored -> List.of();
        };
    }

    default boolean isHidden() {
        return switch (this) {
            case Hidden ignored -> true;
            case Pending ignored -> false;
            case Unavailable ignored -> false;
            case Listed ignored -> false;
        };
    }
}
