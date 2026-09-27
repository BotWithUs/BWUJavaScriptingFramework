package com.botwithus.bot.core.alerts;

import java.time.Duration;

/** Waits. {@code Thread::sleep} in the host; a recorder in tests, so a retry test never sleeps. */
@FunctionalInterface
public interface Sleeper {

    /** Blocks for {@code duration}. */
    void sleep(Duration duration) throws InterruptedException;
}
