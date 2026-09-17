package com.botwithus.bot.core.rpc;

import com.botwithus.bot.api.event.GameEvent;
import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.core.pipe.PipeException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the controller's use of the resolver: that it re-resolves per attempt,
 * reconnects to the name it was handed, and stops when told recovery is
 * impossible instead of retrying forever.
 */
class ReconnectRecoveryPathTest {

    private static final String OUR_PIPE = "BotWithUs_4242";

    /**
     * Stands in for {@code ReconnectPolicy.DEFAULT}'s effectively-unbounded
     * budget. Large enough that "stopped immediately" is unambiguous, small
     * enough that a regression fails on an assertion rather than looping until
     * the suite times out.
     */
    private static final int LARGE_ATTEMPT_BUDGET = 50;

    /** Records which pipe names the controller actually reconnected to. */
    private static final class RecordingReconnector implements ReconnectController.Reconnector {
        private final List<String> targets = new ArrayList<>();
        private final int failures;

        RecordingReconnector(int failures) {
            this.failures = failures;
        }

        @Override
        public void reconnect(String pipeName) {
            targets.add(pipeName);
            if (targets.size() <= failures) {
                throw new PipeException("simulated fail #" + targets.size());
            }
        }
    }

    private static ReconnectPolicy zeroDelayPolicy(int maxAttempts) {
        return new ReconnectPolicy(maxAttempts, 0, 1.0, 0);
    }

    private static ReconnectController controller(ReconnectController.Reconnector r,
                                                  ReconnectController.PipeResolver resolver,
                                                  List<ReconnectState> states,
                                                  int maxAttempts) {
        return new ReconnectController(handler -> { }, r, resolver, "test",
                zeroDelayPolicy(maxAttempts), states::add, ev -> { });
    }

    /**
     * Positive control: with a resolver that always finds the pipe, recovery
     * still works end to end. Without this the give-up assertions below could
     * pass simply because the controller never reconnects at all.
     */
    @Test
    void reconnectsSuccessfullyWhenTheResolverFindsOurPipe() {
        RecordingReconnector r = new RecordingReconnector(0);
        List<ReconnectState> states = new ArrayList<>();

        controller(r, attempt -> new PipeResolution.Found(OUR_PIPE), states, 5)
                .onDisconnectSync(new RuntimeException("drop"));

        assertEquals(List.of(OUR_PIPE), r.targets);
        assertInstanceOf(ReconnectState.Connected.class, states.get(states.size() - 1));
    }

    /**
     * The name must come from the resolver each attempt, not from one captured
     * at construction. A pipe that comes back is the one we reconnect to.
     */
    @Test
    void usesTheNameResolvedForTheCurrentAttempt() {
        RecordingReconnector r = new RecordingReconnector(0);
        List<ReconnectState> states = new ArrayList<>();
        ReconnectController.PipeResolver resolver = attempt ->
                attempt < 3 ? new PipeResolution.NotYet("waiting")
                        : new PipeResolution.Found(OUR_PIPE);

        controller(r, resolver, states, 10).onDisconnectSync(new RuntimeException("drop"));

        assertEquals(List.of(OUR_PIPE), r.targets,
                "should reconnect exactly once, on the attempt that resolved Found");
    }

    /**
     * The headline defect. A Gone verdict must end the loop immediately —
     * not run out a budget of Integer.MAX_VALUE attempts.
     */
    @Test
    void stopsImmediatelyWhenRecoveryIsImpossible() {
        RecordingReconnector r = new RecordingReconnector(0);
        List<ReconnectState> states = new ArrayList<>();
        AtomicInteger resolveCalls = new AtomicInteger();
        ReconnectController.PipeResolver resolver = attempt -> {
            resolveCalls.incrementAndGet();
            return new PipeResolution.Gone("game process 4242 has exited");
        };

        controller(r, resolver, states, LARGE_ATTEMPT_BUDGET)
                .onDisconnectSync(new RuntimeException("drop"));

        assertEquals(1, resolveCalls.get(), "must not keep resolving after Gone");
        assertTrue(r.targets.isEmpty(), "must not reconnect to anything after Gone");
        ReconnectState last = states.get(states.size() - 1);
        ReconnectState.GivingUp gu =
                assertInstanceOf(ReconnectState.GivingUp.class, last,
                        "a terminal failure must surface as GivingUp, not silence");
        assertTrue(gu.lastCause().getMessage().contains("exited"),
                "the give-up cause must carry the reason, so the log is actionable");
    }

    /** A NotYet verdict keeps waiting rather than reconnecting to nothing. */
    @Test
    void doesNotReconnectWhileTheResolverSaysNotYet() {
        RecordingReconnector r = new RecordingReconnector(0);
        List<ReconnectState> states = new ArrayList<>();

        controller(r, attempt -> new PipeResolution.NotYet("waiting"), states, 4)
                .onDisconnectSync(new RuntimeException("drop"));

        assertTrue(r.targets.isEmpty(), "NotYet must never trigger a reconnect");
        assertInstanceOf(ReconnectState.GivingUp.class, states.get(states.size() - 1),
                "exhausting the budget while waiting must still end in GivingUp");
    }
}
