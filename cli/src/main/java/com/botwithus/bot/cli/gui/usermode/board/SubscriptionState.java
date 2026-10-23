package com.botwithus.bot.cli.gui.usermode.board;

/** Where a subscribed script stands on the client the picker was opened for. */
public sealed interface SubscriptionState {

    /** Not on this client; choosing it installs it through the launcher first. */
    record NotInstalled() implements SubscriptionState {}

    /** A copy is already on this client; choosing it starts that copy. */
    record Installed() implements SubscriptionState {}

    /**
     * A Store copy is on this client, but the Store now has a newer build than the
     * one this host installed. Choosing it still starts the installed copy: an
     * update is a separate, explicit install.
     *
     * @param from the build this host installed
     * @param to   the build the Store offers now
     */
    record UpdateAvailable(int from, int to) implements SubscriptionState {}

    /** An install is in flight; the script starts on the client when it lands. */
    record Installing() implements SubscriptionState {}

    /** The last install attempt failed; {@code message} is the installer's reason. Choosing it retries. */
    record Failed(String message) implements SubscriptionState {}

    /** Whether choosing the row can do anything right now. */
    default boolean isChoosable() {
        return switch (this) {
            case Installing ignored -> false;
            case NotInstalled ignored -> true;
            case Installed ignored -> true;
            case UpdateAvailable ignored -> true;
            case Failed ignored -> true;
        };
    }

    /** Whether choosing the row starts an install rather than an installed copy. */
    default boolean needsInstall() {
        return switch (this) {
            case NotInstalled ignored -> true;
            case Failed ignored -> true;
            case Installed ignored -> false;
            case UpdateAvailable ignored -> false;
            case Installing ignored -> false;
        };
    }
}
