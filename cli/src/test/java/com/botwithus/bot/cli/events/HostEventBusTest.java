package com.botwithus.bot.cli.events;

import com.botwithus.bot.cli.events.HostEvent.ClientOpened;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostEventBusTest {

    private static final Duration FLUSH = Duration.ofSeconds(10);
    private static final int PUBLISHERS = 4;
    private static final int EVENTS_EACH = 500;

    private final HostEventBus bus = new HostEventBus();

    @AfterEach
    void closeBus() {
        bus.close();
    }

    @Test
    void everySubscriberSeesEveryEventInTheSameOrder() throws InterruptedException {
        List<HostEvent> first = new CopyOnWriteArrayList<>();
        List<HostEvent> second = new CopyOnWriteArrayList<>();
        bus.subscribe(first::add);
        bus.subscribe(second::add);

        List<Thread> publishers = new ArrayList<>();
        for (int p = 0; p < PUBLISHERS; p++) {
            String pipe = "pipe-" + p;
            publishers.add(Thread.ofPlatform().start(() -> publishRun(pipe)));
        }
        for (Thread t : publishers) {
            t.join();
        }
        assertTrue(bus.flush(FLUSH), "delivery did not finish");

        assertEquals(PUBLISHERS * EVENTS_EACH, first.size(), "an event was lost");
        assertEquals(first, second, "two subscribers saw different orders");
        for (int p = 0; p < PUBLISHERS; p++) {
            assertEquals(expectedRun("pipe-" + p), eventsOf(first, "pipe-" + p),
                    "one publisher's events arrived out of order");
        }
    }

    @Test
    void aThrowingSubscriberDoesNotStarveTheOthers() {
        List<HostEvent> received = new CopyOnWriteArrayList<>();
        bus.subscribe(event -> {
            throw new IllegalStateException("subscriber bug");
        });
        bus.subscribe(received::add);

        bus.publish(opened("a", 1));
        bus.publish(opened("b", 2));
        assertTrue(bus.flush(FLUSH));

        assertEquals(List.of(opened("a", 1), opened("b", 2)), received);
    }

    @Test
    void unsubscribingStopsDelivery() {
        List<HostEvent> received = new CopyOnWriteArrayList<>();
        Runnable unsubscribe = bus.subscribe(received::add);

        bus.publish(opened("a", 1));
        assertTrue(bus.flush(FLUSH));
        unsubscribe.run();
        bus.publish(opened("b", 2));
        assertTrue(bus.flush(FLUSH));

        assertEquals(List.of(opened("a", 1)), received);
    }

    @Test
    void publishDoesNotWaitForASlowSubscriber() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        List<HostEvent> received = new CopyOnWriteArrayList<>();
        bus.subscribe(event -> {
            entered.countDown();
            awaitQuietly(release);
            received.add(event);
        });

        bus.publish(opened("a", 1));
        assertTrue(entered.await(FLUSH.toSeconds(), TimeUnit.SECONDS), "subscriber never ran");
        bus.publish(opened("b", 2));
        assertFalse(bus.flush(Duration.ofMillis(50)), "flush must report a subscriber still busy");

        release.countDown();
        assertTrue(bus.flush(FLUSH));
        assertEquals(List.of(opened("a", 1), opened("b", 2)), received);
    }

    @Test
    void flushFromASubscriberIsRefused() {
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        bus.subscribe(event -> {
            try {
                bus.flush(FLUSH);
            } catch (IllegalStateException e) {
                failures.add(e);
            }
        });

        bus.publish(opened("a", 1));
        assertTrue(bus.flush(FLUSH), "a subscriber that flushes must not wedge the bus");
        assertEquals(1, failures.size());
    }

    @Test
    void aClosedBusDeliversWhatWasQueuedAndDropsTheRest() {
        List<HostEvent> received = new CopyOnWriteArrayList<>();
        bus.subscribe(received::add);

        bus.publish(opened("a", 1));
        bus.close();
        bus.publish(opened("b", 2));

        assertEquals(List.of(opened("a", 1)), received);
        assertThrows(IllegalStateException.class, () -> bus.subscribe(received::add));
    }

    private void publishRun(String pipe) {
        for (HostEvent event : expectedRun(pipe)) {
            bus.publish(event);
        }
    }

    private static List<HostEvent> expectedRun(String pipe) {
        List<HostEvent> run = new ArrayList<>();
        for (int n = 0; n < EVENTS_EACH; n++) {
            run.add(opened(pipe, n));
        }
        return run;
    }

    private static List<HostEvent> eventsOf(List<HostEvent> all, String pipe) {
        return all.stream()
                .filter(e -> switch (e) {
                    case HostEvent.ClientEvent c -> c.client().pipe().equals(pipe);
                    default -> false;
                })
                .toList();
    }

    private static HostEvent opened(String pipe, long second) {
        return new ClientOpened(new ClientRef(pipe), Instant.ofEpochSecond(second));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
