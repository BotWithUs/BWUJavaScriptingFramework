package com.botwithus.bot.cli.settings;

import java.time.Instant;

/**
 * Whether {@code config.properties} on disk matches what {@link HostSettings}
 * holds, for the Settings header's "saving … / saved" line.
 */
public sealed interface SaveStatus {

    /**
     * Disk matches memory.
     *
     * @param at when that became true: the last successful write, or when the file was read
     */
    record Saved(Instant at) implements SaveStatus {}

    /** A change is waiting for, or in the middle of, its write. */
    record Saving() implements SaveStatus {}

    /**
     * The last write failed. The next change, {@link HostSettings#flush()} or
     * {@link HostSettings#close()} tries again.
     *
     * @param message what went wrong, fit to show the user
     * @param at      when it failed
     */
    record Failed(String message, Instant at) implements SaveStatus {}
}
