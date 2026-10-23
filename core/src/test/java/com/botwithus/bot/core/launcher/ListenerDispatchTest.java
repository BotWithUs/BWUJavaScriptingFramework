package com.botwithus.bot.core.launcher;

import com.botwithus.bot.api.script.LauncherEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A listener that falls behind loses the oldest events and is told how many. */
class ListenerDispatchTest {

    private static final int OVERFLOW = 5;

    @Test
    void overflow_dropsTheOldest_andReportsTheCountFirst() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(1);
        List<LauncherEvent> seen = new CopyOnWriteArrayList<>();
        try (ListenerDispatch dispatch = new ListenerDispatch(event -> {
            blocked.countDown();
            awaitQuietly(release);
            seen.add(event);
        })) {
            dispatch.offer(new LauncherEvent.AgentUpdated("first"));
            blocked.await();
            for (int i = 0; i < ListenerDispatch.CAPACITY + OVERFLOW; i++) {
                dispatch.offer(new LauncherEvent.AgentUpdated("e" + i));
            }
            release.countDown();
            FakeLauncherService.await("delivery", Duration.ofSeconds(5),
                    () -> seen.size() == ListenerDispatch.CAPACITY + 2);
        }
        assertEquals(new LauncherEvent.AgentUpdated("first"), seen.get(0));
        assertEquals(new LauncherEvent.EventsDropped(OVERFLOW), seen.get(1));
        assertEquals(new LauncherEvent.AgentUpdated("e" + OVERFLOW), seen.get(2));
    }

    @Test
    void aThrowingListener_keepsGettingEvents() {
        List<LauncherEvent> seen = new CopyOnWriteArrayList<>();
        try (ListenerDispatch dispatch = new ListenerDispatch(event -> {
            seen.add(event);
            throw new IllegalStateException("listener bug");
        })) {
            dispatch.offer(new LauncherEvent.AgentUpdated("a"));
            dispatch.offer(new LauncherEvent.AgentUpdated("b"));
            FakeLauncherService.await("both", Duration.ofSeconds(5), () -> seen.size() == 2);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
