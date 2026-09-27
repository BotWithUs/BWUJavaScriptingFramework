package com.botwithus.bot.core.alerts;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualThreadSchedulerTest {

    private static final Duration DELAY = Duration.ofMillis(50);
    private static final long WAIT_MS = 5_000;

    private final Clock clock = Clock.systemUTC();
    private final VirtualThreadScheduler scheduler = new VirtualThreadScheduler(clock);

    @Test
    void schedule_runsTheTaskAtTheMoment() throws InterruptedException {
        CountDownLatch ran = new CountDownLatch(1);
        long start = System.nanoTime();

        scheduler.schedule(clock.instant().plus(DELAY), ran::countDown);

        assertTrue(ran.await(WAIT_MS, TimeUnit.MILLISECONDS));
        assertTrue(System.nanoTime() - start >= DELAY.toNanos(), "ran early");
    }

    @Test
    void schedule_inThePast_runsAtOnce() throws InterruptedException {
        CountDownLatch ran = new CountDownLatch(1);

        scheduler.schedule(clock.instant().minusSeconds(1), ran::countDown);

        assertTrue(ran.await(WAIT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    void cancel_beforeTheMoment_stopsTheTask() throws InterruptedException {
        AtomicBoolean ran = new AtomicBoolean();
        CountDownLatch control = new CountDownLatch(1);

        scheduler.schedule(clock.instant().plus(DELAY), () -> ran.set(true)).cancel();
        scheduler.schedule(clock.instant().plus(DELAY.multipliedBy(2)), control::countDown);

        assertTrue(control.await(WAIT_MS, TimeUnit.MILLISECONDS));
        assertFalse(ran.get());
    }
}
