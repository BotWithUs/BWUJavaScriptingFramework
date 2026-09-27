package com.botwithus.bot.core.rpc;

import com.botwithus.bot.api.event.ConnectionLostEvent;
import com.botwithus.bot.api.event.GameEvent;
import com.botwithus.bot.api.event.ReconnectStateChangedEvent;
import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.core.pipe.PipeException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

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
        public void reconnect(String pipeName) {
            int n = calls.incrementAndGet();
            if (n <= failures) {
                throw new PipeException("simulated fail #" + n);
            }
        }
    }

    private static final class AlwaysFailingReconnector implements ReconnectController.Reconnector {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public void reconnect(String pipeName) {
            calls.incrementAndGet();
            throw new PipeException("always fails");
        }
    }

    /** Resolver stand-in for tests about backoff/state, not pipe resolution. */
    private static ReconnectController.PipeResolver alwaysFound() {
        return attempt -> new PipeResolution.Found("BotWithUs_1234");
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
                handler -> { /* unused */ }, r, alwaysFound(), "test", zeroDelayPolicy(10),
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
                handler -> {}, r, alwaysFound(), "test", zeroDelayPolicy(3),
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
                handler -> {}, r, alwaysFound(), "test", zeroDelayPolicy(5),
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
                handler -> {}, r, alwaysFound(), "test", zeroDelayPolicy(5),
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
                handler -> {}, r, alwaysFound(), "test", zeroDelayPolicy(100),
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
                armer, pipeName -> {}, alwaysFound(), "test", zeroDelayPolicy(0),
                state -> {}, ev -> {});
        controller.arm();
        assertEquals(1, wired.size());
    }

    @Test
    void initialStateIsConnected() {
        ReconnectController controller = new ReconnectController(
                handler -> {}, pipeName -> {}, alwaysFound(), "test", zeroDelayPolicy(0),
                state -> {}, ev -> {});
        assertInstanceOf(ReconnectState.Connected.class, controller.currentState());
    }

    @Test
    void stateListenerExceptionsDoNotPoisonRecovery() {
        CountingReconnector r = new CountingReconnector(0);
        ReconnectController controller = new ReconnectController(
                handler -> {}, r, alwaysFound(), "test", zeroDelayPolicy(5),
                state -> { throw new RuntimeException("listener boom"); },
                ev -> {});
        controller.onDisconnectSync(new RuntimeException("drop"));
        assertInstanceOf(ReconnectState.Connected.class, controller.currentState());
        assertEquals(1, r.calls.get());
    }

    // ── Retry now, stop retrying, and a policy read per recovery ──────────

    /**
     * A back-off no test may sit through. A test that needs the controller to
     * be mid-sleep uses it, so finishing within {@link #PROMPT_MS} proves the
     * sleep was cut short rather than waited out.
     */
    private static final long LONG_BACKOFF_MS = 600_000L;
    /** How long a test waits for a state the controller should reach at once. */
    private static final long PROMPT_MS = 5_000L;
    private static final long POLL_MS = 5L;
    private static final int MANY_FAILURES = 200;
    private static final int FIRST_BUDGET = 2;
    private static final int SECOND_BUDGET = 4;

    /** Records every state and lets a test wait for the one it expects. */
    private static final class StateLog implements Consumer<ReconnectState> {
        private final List<ReconnectState> states = new CopyOnWriteArrayList<>();

        @Override
        public void accept(ReconnectState state) {
            states.add(state);
        }

        /** Waits until {@code count} states have matched {@code match}. */
        void await(Predicate<ReconnectState> match, int count) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROMPT_MS);
            while (count(match) < count) {
                assertTrue(System.nanoTime() < deadline, "state never reached; saw " + states);
                pause();
            }
        }

        void await(Predicate<ReconnectState> match) {
            await(match, 1);
        }

        long count(Predicate<ReconnectState> match) {
            return states.stream().filter(match).count();
        }

        ReconnectState last() {
            return states.getLast();
        }

        /** The states recorded after the first one matching {@code match}. */
        List<ReconnectState> after(Predicate<ReconnectState> match) {
            List<ReconnectState> snapshot = List.copyOf(states);
            for (int i = 0; i < snapshot.size(); i++) {
                if (match.test(snapshot.get(i))) {
                    return snapshot.subList(i + 1, snapshot.size());
                }
            }
            return List.of();
        }
    }

    /** Fails until {@link #heal()} is called, then succeeds. */
    private static final class HealableReconnector implements ReconnectController.Reconnector {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicBoolean healthy = new AtomicBoolean();

        @Override
        public void reconnect(String pipeName) {
            calls.incrementAndGet();
            if (!healthy.get()) {
                throw new PipeException("still down");
            }
        }

        void heal() {
            healthy.set(true);
        }
    }

    private static void pause() {
        try {
            Thread.sleep(POLL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting", e);
        }
    }

    private static boolean isConnected(ReconnectState state) {
        return switch (state) {
            case ReconnectState.Connected c -> true;
            default -> false;
        };
    }

    private static boolean isReconnecting(ReconnectState state) {
        return switch (state) {
            case ReconnectState.Reconnecting r -> true;
            default -> false;
        };
    }

    private static boolean isGivingUp(ReconnectState state) {
        return switch (state) {
            case ReconnectState.GivingUp g -> true;
            default -> false;
        };
    }

    private static int attemptOf(ReconnectState state) {
        return switch (state) {
            case ReconnectState.Reconnecting r -> r.attempt();
            default -> throw new AssertionError("not reconnecting: " + state);
        };
    }

    private static ReconnectPolicy longBackoffPolicy() {
        return new ReconnectPolicy(ReconnectPolicy.UNLIMITED, LONG_BACKOFF_MS, 1.0, LONG_BACKOFF_MS);
    }

    /** Drops the connection through the armed handler, as production does, so recovery runs in the background. */
    private static void dropThroughArmedHandler(AtomicReference<Consumer<Throwable>> handler) {
        handler.get().accept(new RuntimeException("drop"));
    }

    @Test
    void retryNow_duringABackOffSleep_attemptsAtOnce() {
        StateLog states = new StateLog();
        CountingReconnector r = new CountingReconnector(0);
        AtomicReference<Consumer<Throwable>> handler = new AtomicReference<>();
        ReconnectController controller = new ReconnectController(handler::set, r, alwaysFound(), "test",
                longBackoffPolicy(), states, ev -> { });
        controller.arm();
        try {
            dropThroughArmedHandler(handler);
            states.await(ReconnectControllerTest::isReconnecting);

            assertEquals(ReconnectController.RetryOutcome.WOKEN, controller.retryNow());

            states.await(ReconnectControllerTest::isConnected);
            assertEquals(1, r.calls.get());
        } finally {
            controller.close();
        }
    }

    @Test
    void retryNow_afterGivingUp_startsAFreshRecovery() {
        StateLog states = new StateLog();
        HealableReconnector r = new HealableReconnector();
        ReconnectController controller = new ReconnectController(
                handler -> { }, r, alwaysFound(), "test", zeroDelayPolicy(FIRST_BUDGET), states, ev -> { });
        controller.onDisconnectSync(new RuntimeException("drop"));
        assertTrue(isGivingUp(states.last()));
        r.heal();

        assertEquals(ReconnectController.RetryOutcome.RESTARTED, controller.retryNow());

        states.await(ReconnectControllerTest::isConnected);
        assertEquals(FIRST_BUDGET + 1, r.calls.get(), "the failed attempts, then one in the fresh recovery");
        List<ReconnectState> afterGivingUp = states.after(ReconnectControllerTest::isGivingUp);
        assertEquals(1, attemptOf(afterGivingUp.getFirst()), "a fresh recovery counts from one again");
    }

    @Test
    void retryNow_afterTheProcessExited_reportsTheClientGoneAndDoesNotRetry() {
        StateLog states = new StateLog();
        AtomicInteger resolves = new AtomicInteger();
        ReconnectController.PipeResolver exited = attempt -> {
            resolves.incrementAndGet();
            return new PipeResolution.Gone(PipeResolution.Gone.Reason.PROCESS_EXITED, "process exited");
        };
        ReconnectController controller = new ReconnectController(
                handler -> { }, pipeName -> { }, exited, "test", zeroDelayPolicy(5), states, ev -> { });
        controller.onDisconnectSync(new RuntimeException("drop"));

        assertTrue(controller.isClientGone());
        assertEquals(ReconnectController.RetryOutcome.CLIENT_GONE, controller.retryNow());
        assertEquals(1, resolves.get(), "a retry that cannot succeed must not be attempted");
    }

    @Test
    void retryNow_afterThePipeWasNotReopenedInTime_canStillRecover() {
        StateLog states = new StateLog();
        AtomicBoolean reopened = new AtomicBoolean();
        ReconnectController.PipeResolver resolver = attempt -> reopened.get()
                ? new PipeResolution.Found("BotWithUs_1234")
                : new PipeResolution.Gone(PipeResolution.Gone.Reason.PIPE_NOT_REOPENED, "not yet");
        ReconnectController controller = new ReconnectController(
                handler -> { }, pipeName -> { }, resolver, "test", zeroDelayPolicy(5), states, ev -> { });
        controller.onDisconnectSync(new RuntimeException("drop"));
        assertFalse(controller.isClientGone(), "the process is alive, so retrying is still worth offering");
        reopened.set(true);

        assertEquals(ReconnectController.RetryOutcome.RESTARTED, controller.retryNow());

        states.await(ReconnectControllerTest::isConnected);
    }

    @Test
    void retryNow_whileConnected_hasNothingToDo() {
        List<GameEvent> events = new ArrayList<>();
        ReconnectController controller = new ReconnectController(
                handler -> { }, pipeName -> { }, alwaysFound(), "test", zeroDelayPolicy(5),
                state -> { }, events::add);

        assertEquals(ReconnectController.RetryOutcome.NOT_NEEDED, controller.retryNow());
        assertTrue(events.isEmpty());
    }

    @Test
    void retryNow_afterClose_isRefused() {
        ReconnectController controller = new ReconnectController(
                handler -> { }, pipeName -> { }, alwaysFound(), "test", zeroDelayPolicy(5),
                state -> { }, ev -> { });
        controller.close();

        assertEquals(ReconnectController.RetryOutcome.CLOSED, controller.retryNow());
    }

    @Test
    void stopRetrying_publishesGivingUpAndMakesNoFurtherAttempt() {
        StateLog states = new StateLog();
        List<GameEvent> events = new CopyOnWriteArrayList<>();
        CountingReconnector r = new CountingReconnector(0);
        AtomicReference<Consumer<Throwable>> handler = new AtomicReference<>();
        ReconnectController controller = new ReconnectController(handler::set, r, alwaysFound(), "test",
                longBackoffPolicy(), states, events::add);
        controller.arm();
        try {
            dropThroughArmedHandler(handler);
            states.await(ReconnectControllerTest::isReconnecting);

            assertTrue(controller.stopRetrying());

            states.await(ReconnectControllerTest::isGivingUp);
            assertTrue(isGivingUp(controller.currentState()), "stopping must be visible, not silent");
            assertTrue(events.stream().anyMatch(ReconnectControllerTest::isGivingUpEvent),
                    "subscribers on the connection's bus must hear it too");
            assertEquals(0, r.calls.get());
        } finally {
            controller.close();
        }
    }

    private static boolean isGivingUpEvent(GameEvent event) {
        return switch (event) {
            case ReconnectStateChangedEvent changed -> isGivingUp(changed.state());
            default -> false;
        };
    }

    @Test
    void stopRetrying_thenRetryNow_recovers() {
        StateLog states = new StateLog();
        CountingReconnector r = new CountingReconnector(0);
        AtomicReference<Consumer<Throwable>> handler = new AtomicReference<>();
        ReconnectController controller = new ReconnectController(handler::set, r, alwaysFound(), "test",
                longBackoffPolicy(), states, ev -> { });
        controller.arm();
        try {
            dropThroughArmedHandler(handler);
            states.await(ReconnectControllerTest::isReconnecting);
            assertTrue(controller.stopRetrying());

            ReconnectController.RetryOutcome outcome = controller.retryNow();

            assertTrue(outcome == ReconnectController.RetryOutcome.WOKEN
                    || outcome == ReconnectController.RetryOutcome.RESTARTED, "got " + outcome);
            states.await(ReconnectControllerTest::isConnected);
            assertEquals(1, r.calls.get());
        } finally {
            controller.close();
        }
    }

    @Test
    void stopRetrying_withNothingInProgress_changesNothing() {
        List<ReconnectState> states = new ArrayList<>();
        ReconnectController controller = new ReconnectController(
                handler -> { }, pipeName -> { }, alwaysFound(), "test", zeroDelayPolicy(5),
                states::add, ev -> { });

        assertFalse(controller.stopRetrying());
        assertTrue(states.isEmpty());
        assertTrue(isConnected(controller.currentState()));
    }

    @Test
    void unlimitedPolicy_keepsTryingPastAnyFixedBudget() {
        List<ReconnectState> states = new ArrayList<>();
        CountingReconnector r = new CountingReconnector(MANY_FAILURES);
        ReconnectController controller = new ReconnectController(
                handler -> { }, r, alwaysFound(), "test", zeroDelayPolicy(ReconnectPolicy.UNLIMITED),
                states::add, ev -> { });

        controller.onDisconnectSync(new RuntimeException("drop"));

        assertEquals(MANY_FAILURES + 1, r.calls.get());
        assertTrue(isConnected(controller.currentState()));
        assertTrue(states.stream().noneMatch(ReconnectControllerTest::isGivingUp));
    }

    @Test
    void maxAttempts_isEmptyForAnUnlimitedPolicy() {
        ReconnectController unlimited = new ReconnectController(
                handler -> { }, pipeName -> { }, alwaysFound(), "test",
                zeroDelayPolicy(ReconnectPolicy.UNLIMITED), state -> { }, ev -> { });
        ReconnectController capped = new ReconnectController(
                handler -> { }, pipeName -> { }, alwaysFound(), "test", zeroDelayPolicy(3),
                state -> { }, ev -> { });

        assertEquals(OptionalInt.empty(), unlimited.maxAttempts());
        assertEquals(OptionalInt.of(3), capped.maxAttempts());
    }

    @Test
    void aPolicyChange_appliesFromTheNextRecoveryNotTheCurrentOne() {
        StateLog states = new StateLog();
        AtomicReference<ReconnectPolicy> policy = new AtomicReference<>(zeroDelayPolicy(FIRST_BUDGET));
        AtomicInteger calls = new AtomicInteger();
        ReconnectController.Reconnector changesPolicyMidRecovery = pipeName -> {
            calls.incrementAndGet();
            policy.set(zeroDelayPolicy(SECOND_BUDGET));
            throw new PipeException("down");
        };
        ReconnectController controller = new ReconnectController(
                handler -> { }, changesPolicyMidRecovery, alwaysFound(), "test", policy::get,
                states, ev -> { });

        controller.onDisconnectSync(new RuntimeException("drop"));

        assertEquals(FIRST_BUDGET, calls.get(), "the recovery in progress keeps the budget it started with");
        assertEquals(OptionalInt.of(FIRST_BUDGET), controller.maxAttempts());

        controller.retryNow();
        states.await(ReconnectControllerTest::isGivingUp, 2);

        assertEquals(FIRST_BUDGET + SECOND_BUDGET, calls.get(), "the next recovery reads the changed budget");
        assertEquals(OptionalInt.of(SECOND_BUDGET), controller.maxAttempts());
    }

    /**
     * The event sink reaches script code, which may be slow. The render thread
     * calls retry and stop, so neither may wait for a listener to return.
     */
    @Test
    void retryNowAndStopRetrying_neverWaitForASlowListener() throws InterruptedException {
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        StateLog states = new StateLog();
        Consumer<ReconnectState> slowListener = state -> {
            states.accept(state);
            if (isReconnecting(state)) {
                listenerEntered.countDown();
                awaitQuietly(releaseListener);
            }
        };
        AtomicReference<Consumer<Throwable>> handler = new AtomicReference<>();
        ReconnectController controller = new ReconnectController(handler::set, pipeName -> { },
                alwaysFound(), "test", longBackoffPolicy(), slowListener, ev -> { });
        controller.arm();
        try {
            dropThroughArmedHandler(handler);
            assertTrue(listenerEntered.await(PROMPT_MS, TimeUnit.MILLISECONDS));

            assertTimeoutPreemptively(Duration.ofMillis(PROMPT_MS), () -> {
                assertTrue(controller.stopRetrying());
                assertEquals(ReconnectController.RetryOutcome.WOKEN, controller.retryNow());
            }, "a listener still running must not hold up retry or stop");

            releaseListener.countDown();
            states.await(ReconnectControllerTest::isConnected);
        } finally {
            releaseListener.countDown();
            controller.close();
        }
    }

    /**
     * The transport re-arms its disconnect report on a successful reconnect, so
     * a pipe that comes back and drops at once reports again while the recovery
     * is still finishing. That drop must start another recovery, not vanish and
     * leave a dead connection showing {@code Connected}.
     */
    @Test
    void aDropRightAfterASuccessfulReconnect_isRecoveredToo() {
        StateLog states = new StateLog();
        AtomicReference<Consumer<Throwable>> handler = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        ReconnectController.Reconnector dropsAgainOnce = pipeName -> {
            if (calls.incrementAndGet() == 1) {
                handler.get().accept(new PipeException("dropped again"));
            }
        };
        ReconnectController controller = new ReconnectController(handler::set, dropsAgainOnce,
                alwaysFound(), "test", zeroDelayPolicy(5), states, ev -> { });
        controller.arm();

        controller.onDisconnectSync(new RuntimeException("drop"));

        assertEquals(2, states.count(ReconnectControllerTest::isDisconnected), "saw " + states.states);
        assertEquals(2, calls.get());
        assertTrue(isConnected(controller.currentState()));
    }

    private static boolean isDisconnected(ReconnectState state) {
        return switch (state) {
            case ReconnectState.Disconnected d -> true;
            default -> false;
        };
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            assertTrue(latch.await(PROMPT_MS, TimeUnit.MILLISECONDS), "never released");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
