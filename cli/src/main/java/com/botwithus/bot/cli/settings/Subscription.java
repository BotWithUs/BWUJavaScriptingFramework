package com.botwithus.bot.cli.settings;

/** Handle for a {@link HostSettings} listener; {@link #close()} removes it. Idempotent. */
@FunctionalInterface
public interface Subscription extends AutoCloseable {

    @Override
    void close();
}
