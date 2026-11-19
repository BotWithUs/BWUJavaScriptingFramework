package com.botwithus.bot.core.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-owner lanes behind {@code ui.event} delivery: a full lane drops with a
 * WARN instead of blocking, and only its own owner is affected.
 */
class UiEventDispatcherTest {

    private static final int CAPACITY = 2;
    private static final int SUBMITTED = 5;
    private static final long AWAIT_SECONDS = 5L;
    private static final long QUIET_MS = 300L;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    // rule-exception: {rule:no-casts} — attaching an appender needs logback's own Logger,
    // which the SLF4J facade cannot express; same exception as GameAPIImplDrawTest.
    private final Logger logger = (Logger) LoggerFactory.getLogger(UiEventDispatcher.class);

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    @Test
    @DisplayName("a full lane drops with a WARN, and another owner's lane still delivers")
    void fullLane_dropsAndWarns_otherLaneUnaffected() throws InterruptedException {
        UiEventDispatcher dispatcher = new UiEventDispatcher(CAPACITY);
        CountDownLatch blocking = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Integer> ran = new CopyOnWriteArrayList<>();
        CountDownLatch otherRan = new CountDownLatch(1);

        dispatcher.submit("slow", () -> {
            blocking.countDown();
            await(release);
        });
        assertTrue(blocking.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        CountDownLatch queuedRan = new CountDownLatch(CAPACITY);
        for (int i = 0; i < SUBMITTED; i++) {
            int n = i;
            dispatcher.submit("slow", () -> {
                ran.add(n);
                queuedRan.countDown();
            });
        }
        dispatcher.submit("other", otherRan::countDown);

        assertTrue(otherRan.await(AWAIT_SECONDS, TimeUnit.SECONDS), "the other owner's lane was held up");
        // Only this lane's: lanes left over from other test classes in the JVM log on the same logger.
        long warnings = logs.list.stream()
                .filter(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("for slow "))
                .count();
        assertEquals(SUBMITTED - CAPACITY, warnings, "one WARN per dropped event");

        // Wait on the queued tasks themselves: a sentinel submitted now could find the
        // queue still full and be dropped too.
        release.countDown();
        assertTrue(queuedRan.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(List.of(0, 1), ran, "the queued events ran in order; the rest were dropped");
    }

    @Test
    @DisplayName("a closed owner gets no new lane: its events are dropped until it is reopened")
    void closedOwner_getsNoNewLane() throws InterruptedException {
        UiEventDispatcher dispatcher = new UiEventDispatcher(CAPACITY);
        CountDownLatch ran = new CountDownLatch(1);
        dispatcher.close("released");

        dispatcher.submit("released", ran::countDown);
        assertFalse(ran.await(QUIET_MS, TimeUnit.MILLISECONDS), "an event reached a released owner");

        dispatcher.reopen("released");
        dispatcher.submit("released", ran::countDown);
        assertTrue(ran.await(AWAIT_SECONDS, TimeUnit.SECONDS), "positive control: a reopened owner hears again");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
