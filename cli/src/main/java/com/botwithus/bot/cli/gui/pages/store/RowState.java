package com.botwithus.bot.cli.gui.pages.store;

/** Where a catalogue script stands on this PC. */
public sealed interface RowState {

    /**
     * No copy is loaded here.
     *
     * @param wasInstalledBefore the Store installed it on this PC earlier, but it is not loaded
     *                           now: Store scripts live in memory, so a host restart unloads them
     */
    record NotInstalled(boolean wasInstalledBefore) implements RowState {}

    /** A copy is loaded here and nothing says a newer build is out. */
    record Installed() implements RowState {}

    /**
     * A Store copy is loaded here, and the Store has a newer build.
     *
     * @param from the build this host installed
     * @param to   the build the Store offers now
     */
    record UpdateAvailable(int from, int to) implements RowState {}

    /** Whether a copy is loaded on this PC, up to date or not. */
    default boolean isOnThisPc() {
        return switch (this) {
            case NotInstalled ignored -> false;
            case Installed ignored -> true;
            case UpdateAvailable ignored -> true;
        };
    }

    /** Whether the Store installed it here before and it is no longer loaded. */
    default boolean wasInstalledBefore() {
        return false;
    }

    default boolean hasUpdate() {
        return switch (this) {
            case NotInstalled ignored -> false;
            case Installed ignored -> false;
            case UpdateAvailable ignored -> true;
        };
    }
}
