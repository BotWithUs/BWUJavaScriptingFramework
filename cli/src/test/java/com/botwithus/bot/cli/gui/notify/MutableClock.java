package com.botwithus.bot.cli.gui.notify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock the test moves by hand, so toast lifetimes can be stepped through. */
final class MutableClock extends Clock {

    static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private Instant now = START;

    void advance(Duration by) {
        now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
