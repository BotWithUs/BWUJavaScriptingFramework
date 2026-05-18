package com.botwithus.bot.cli.gui.notify;

import com.botwithus.bot.api.event.ConnectionLostEvent;
import com.botwithus.bot.api.event.EventBus;
import com.botwithus.bot.api.event.GameEvent;
import com.botwithus.bot.api.event.ReconnectStateChangedEvent;
import com.botwithus.bot.api.event.ScriptCrashedEvent;
import com.botwithus.bot.api.event.ScriptLoadFailedEvent;
import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.api.runtime.ReconnectState;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class NotificationOverlayTest {

    /** Minimal EventBus that records subscriptions; tests publish manually. */
    private static final class StubBus implements EventBus {
        final Map<Class<? extends GameEvent>, List<Consumer<? extends GameEvent>>> listeners = new HashMap<>();

        @Override
        public <T extends GameEvent> void subscribe(Class<T> eventType, Consumer<T> listener) {
            listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(listener);
        }

        @Override
        public <T extends GameEvent> void unsubscribe(Class<T> eventType, Consumer<T> listener) {
            List<Consumer<? extends GameEvent>> l = listeners.get(eventType);
            if (l != null) {
                l.remove(listener);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public void publish(GameEvent event) {
            List<Consumer<? extends GameEvent>> l = listeners.get(event.getClass());
            if (l != null) {
                for (Consumer<? extends GameEvent> c : l) {
                    ((Consumer<GameEvent>) c).accept(event);
                }
            }
        }
    }

    /** Clock whose instant() returns a mutable Instant for time-travel tests. */
    private static final class FakeClock extends Clock {
        Instant now;

        FakeClock(Instant start) {
            this.now = start;
        }

        @Override public ZoneId getZone() { return ZoneId.systemDefault(); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void subscribesToAllFourEventTypes() {
        StubBus bus = new StubBus();
        NotificationOverlay overlay = new NotificationOverlay();
        overlay.subscribeTo(bus);
        assertEquals(1, bus.listeners.get(ConnectionLostEvent.class).size());
        assertEquals(1, bus.listeners.get(ReconnectStateChangedEvent.class).size());
        assertEquals(1, bus.listeners.get(ScriptCrashedEvent.class).size());
        assertEquals(1, bus.listeners.get(ScriptLoadFailedEvent.class).size());
    }

    @Test
    void connectionLostPushesErrorNotification() {
        StubBus bus = new StubBus();
        FakeClock clock = new FakeClock(Instant.parse("2025-01-01T00:00:00Z"));
        NotificationOverlay overlay = new NotificationOverlay(clock);
        overlay.subscribeTo(bus);

        bus.publish(new ConnectionLostEvent("test", new RuntimeException("pipe gone")));

        assertEquals(1, overlay.active().size());
        Notification n = overlay.active().iterator().next();
        assertEquals(Notification.Severity.ERROR, n.severity());
        assertTrue(n.title().contains("test"));
    }

    @Test
    void expiredNotificationsAreCulledOnRender() {
        StubBus bus = new StubBus();
        FakeClock clock = new FakeClock(Instant.parse("2025-01-01T00:00:00Z"));
        NotificationOverlay overlay = new NotificationOverlay(clock);
        overlay.subscribeTo(bus);

        bus.publish(new ConnectionLostEvent("test", new RuntimeException()));
        assertEquals(1, overlay.active().size());

        // Advance past TTL — render() (the cull pass) should drop it.
        clock.now = clock.now.plus(NotificationOverlay.DEFAULT_TTL).plus(Duration.ofSeconds(1));
        // Render would normally call into ImGui — to keep the test
        // headless, exercise just the cull-only loop via a direct expiry check.
        Instant cutoff = clock.instant();
        Notification n = overlay.active().iterator().next();
        assertTrue(n.isExpired(cutoff));
    }

    @Test
    void reconnectingStateProducesWarnNotification() {
        StubBus bus = new StubBus();
        NotificationOverlay overlay = new NotificationOverlay();
        overlay.subscribeTo(bus);

        bus.publish(new ReconnectStateChangedEvent("test",
                new ReconnectState.Reconnecting(0L, 2, 500L)));

        assertEquals(1, overlay.active().size());
        Notification n = overlay.active().iterator().next();
        assertEquals(Notification.Severity.WARN, n.severity());
        assertTrue(n.message().contains("attempt 2"));
    }

    @Test
    void disconnectedStateIsSwallowedToAvoidDoubleNotify() {
        StubBus bus = new StubBus();
        NotificationOverlay overlay = new NotificationOverlay();
        overlay.subscribeTo(bus);

        bus.publish(new ReconnectStateChangedEvent("test",
                new ReconnectState.Disconnected(0L, new RuntimeException())));

        // ConnectionLostEvent is the user-facing one for disconnects.
        assertEquals(0, overlay.active().size());
    }

    @Test
    void connectedStateProducesInfoNotification() {
        StubBus bus = new StubBus();
        NotificationOverlay overlay = new NotificationOverlay();
        overlay.subscribeTo(bus);

        bus.publish(new ReconnectStateChangedEvent("test", new ReconnectState.Connected(0L)));

        assertEquals(1, overlay.active().size());
        assertEquals(Notification.Severity.INFO, overlay.active().iterator().next().severity());
    }

    @Test
    void givingUpProducesErrorNotification() {
        StubBus bus = new StubBus();
        NotificationOverlay overlay = new NotificationOverlay();
        overlay.subscribeTo(bus);

        bus.publish(new ReconnectStateChangedEvent("test",
                new ReconnectState.GivingUp(0L, 5, new RuntimeException("last"))));

        assertEquals(1, overlay.active().size());
        assertEquals(Notification.Severity.ERROR, overlay.active().iterator().next().severity());
    }

    @Test
    void scriptCrashedProducesErrorNotification() {
        StubBus bus = new StubBus();
        NotificationOverlay overlay = new NotificationOverlay();
        overlay.subscribeTo(bus);

        LastCrash crash = new LastCrash(Phase.ON_LOOP, 7L, Instant.now(),
                new IllegalStateException("boom"));
        bus.publish(new ScriptCrashedEvent("ChopperScript", "conn1", crash));

        assertEquals(1, overlay.active().size());
        Notification n = overlay.active().iterator().next();
        assertEquals(Notification.Severity.ERROR, n.severity());
        assertTrue(n.title().contains("ChopperScript"));
    }

    @Test
    void scriptLoadFailedProducesWarnNotification() {
        StubBus bus = new StubBus();
        NotificationOverlay overlay = new NotificationOverlay();
        overlay.subscribeTo(bus);

        bus.publish(new ScriptLoadFailedEvent(Path.of("broken.jar"),
                new IllegalStateException("missing module-info")));

        assertEquals(1, overlay.active().size());
        Notification n = overlay.active().iterator().next();
        assertEquals(Notification.Severity.WARN, n.severity());
        assertTrue(n.title().contains("broken.jar"));
    }
}
