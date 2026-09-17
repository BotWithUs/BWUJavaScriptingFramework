package com.botwithus.bot.core.rpc;

import com.botwithus.bot.api.event.ConnectionLostEvent;
import com.botwithus.bot.api.event.GameEvent;
import com.botwithus.bot.api.event.ReconnectStateChangedEvent;
import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.core.pipe.PipeException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReconnectControllerTest {

    /**
     * Reconnector that throws {@code failures} times then succeeds. Returns
     * how many calls have happened so the test can assert call count.
     */
    private static final class CountingReconnector implements ReconnectController.Reconnector {
        private final int failures;
        private final AtomicInteger calls = new AtomicInteger();

        CountingReconnector(int failures) {
            this.failures = failures;
        }

        @Override
        public void reconnect() {
            int n = calls.incrementAndGet();
            if (n <= failures) {
                throw new PipeException("simulated fail #" + n);
            }
        }
    }

    private static final class AlwaysFailingReconnector implements ReconnectController.Reconnector {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public void reconnect() {
            calls.incrementAndGet();
            throw new PipeException("always fails");
        }
    }

    private static ReconnectPolicy zeroDelayPolicy(int maxAttempts) {
        return new ReconnectPolicy(maxAttempts, 0, 1.0, 0);
    }

    @Test
    void succeedsAfterTransientFailures() {
        CountingReconnector r = new CountingReconnector(2);
        List<ReconnectState> states = new ArrayList<>();
        List<GameEvent> events = new ArrayList<>();

        ReconnectController controller = new ReconnectController(
                handler -> { /* unused */ }, r, "test", zeroDelayPolicy(10),
                states::add, events::add);

        controller.onDisconnectSync(new RuntimeException("initial drop"));

        // Two failed attempts plus the success
        assertEquals(3, r.calls.get());

        // Transitions: Disconnected, Reconnecting(1), Reconnecting(2), Reconnecting(3), Connected
        assertTrue(states.size() >= 4);
        assertInstanceOf(ReconnectState.Disconnected.class, states.get(0));
        assertInstanceOf(ReconnectState.Reconnecting.class, states.get(1));
        ReconnectState last = states.get(states.size() - 1);
        assertInstanceOf(ReconnectState.Connected.class, last);
        assertInstanceOf(ReconnectState.Connected.class, controller.currentState());
    }

    @Test
    void givesUpAfterExhaustedAttempts() {
        AlwaysFailingReconnector r = new AlwaysFailingReconnector();
        List<ReconnectState> states = new ArrayList<>();
        List<GameEvent> events = new ArrayList<>();

        ReconnectController controller = new ReconnectController(
                handler -> {}, r, "test", zeroDelayPolicy(3),
                states::add, events::add);

        controller.onDisconnectSync(new RuntimeException("drop"));

        assertEquals(3, r.calls.get());
        ReconnectState last = states.get(states.size() - 1);
        ReconnectState.GivingUp gu = assertInstanceOf(ReconnectState.GivingUp.class, last);
        assertEquals(3, gu.attempts());
        assertInstanceOf(ReconnectState.GivingUp.class, controller.currentState());
    }

    @Test
    void publishesConnectionLostEvent() {
        CountingReconnector r = new CountingReconnector(0);
        List<GameEvent> events = new ArrayList<>();

        ReconnectController controller = new ReconnectController(
                handler -> {}, r, "test", zeroDelayPolicy(5),
                state -> {}, events::add);

        RuntimeException cause = new RuntimeException("drop");
        controller.onDisconnectSync(cause);

        ConnectionLostEvent lostEvent = events.stream()
                .filter(ConnectionLostEvent.class::isInstance)
                .map(ConnectionLostEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("test", lostEvent.connectionName());
        assertSame(cause, lostEvent.cause());
    }

    @Test
    void publishesReconnectStateChangedEventsForEachTransition() {
        CountingReconnector r = new CountingReconnector(1);
        List<GameEvent> events = new ArrayList<>();

        ReconnectController controller = new ReconnectController(
                handler -> {}, r, "test", zeroDelayPolicy(5),
                state -> {}, events::add);

        controller.onDisconnectSync(new RuntimeException("drop"));

        long count = events.stream()
                .filter(ReconnectStateChangedEvent.class::isInstance)
                .count();
        // Disconnected + Reconnecting(1) + Reconnecting(2) + Connected = 4
        assertEquals(4, count);
    }

    @Test
    void closePreventsFurtherAttempts() {
        AlwaysFailingReconnector r = new AlwaysFailingReconnector();
        ReconnectController controller = new ReconnectController(
                handler -> {}, r, "test", zeroDelayPolicy(100),
                state -> {}, ev -> {});
        controller.close();
        controller.onDisconnectSync(new RuntimeException("drop"));
        assertEquals(0, r.calls.get());
    }

    @Test
    void armInstallsDisconnectHandler() {
        List<Throwable> wired = new ArrayList<>();
        ReconnectController.DisconnectArmer armer = handler -> wired.add(new RuntimeException("captured"));
        ReconnectController controller = new ReconnectController(
                armer, () -> {}, "test", zeroDelayPolicy(0),
                state -> {}, ev -> {});
        controller.arm();
        assertEquals(1, wired.size());
    }

    @Test
    void initialStateIsConnected() {
        ReconnectController controller = new ReconnectController(
                handler -> {}, () -> {}, "test", zeroDelayPolicy(0),
                state -> {}, ev -> {});
        assertInstanceOf(ReconnectState.Connected.class, controller.currentState());
    }

    @Test
    void stateListenerExceptionsDoNotPoisonRecovery() {
        CountingReconnector r = new CountingReconnector(0);
        ReconnectController controller = new ReconnectController(
                handler -> {}, r, "test", zeroDelayPolicy(5),
                state -> { throw new RuntimeException("listener boom"); },
                ev -> {});
        controller.onDisconnectSync(new RuntimeException("drop"));
        assertInstanceOf(ReconnectState.Connected.class, controller.currentState());
        assertEquals(1, r.calls.get());
    }
}
