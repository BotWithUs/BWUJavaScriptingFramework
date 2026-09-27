package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Liveness;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.api.runtime.ScriptHealth;
import com.botwithus.bot.core.runtime.RunnerLiveness;
import com.botwithus.bot.core.runtime.ScriptProfiler;
import com.botwithus.bot.core.runtime.ScriptRunner;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which of the five states a script row shows, derived from a runner the way
 * the live board reads it. The runner is a mock; its liveness, health and
 * profiler are the real runtime types.
 */
class ClientRowsTest {

    private static final Instant STARTED = Instant.parse("2026-09-26T14:00:00Z");
    private static final Duration RUN_FOR = Duration.ofMinutes(41).plusSeconds(7);
    private static final Instant NOW = STARTED.plus(RUN_FOR);
    private static final ZoneId ZONE = ZoneOffset.UTC;
    private static final long STALL_SECONDS = 38;
    private static final long LOOP_NANOS = 142_000_000L;
    private static final ScriptInfo WOODCUTTING =
            new ScriptInfo("Woodcutting", "BotWithUs", "2.0", ScriptCategory.WOODCUTTING, "", 6, true);

    /** A runner mock whose liveness, health and profiler are real. */
    private record Runner(ScriptRunner runner, RunnerLiveness liveness, ScriptProfiler profiler) {

        static Runner started(Instant at, boolean running) {
            ScriptRunner r = mock(ScriptRunner.class);
            RunnerLiveness liveness = new RunnerLiveness();
            ScriptProfiler profiler = new ScriptProfiler();
            when(r.lastStartedAt()).thenReturn(at);
            when(r.isRunning()).thenReturn(running);
            when(r.livenessState()).thenReturn(liveness);
            when(r.liveness()).thenAnswer(call -> liveness.get());
            when(r.getProfiler()).thenReturn(profiler);
            when(r.health()).thenReturn(ScriptHealth.HEALTHY);
            return new Runner(r, liveness, profiler);
        }

        Runner crashedAt(Instant when) {
            when(runner.health()).thenReturn(ScriptHealth.HEALTHY.withCrash(
                    new LastCrash(Phase.ON_LOOP, 0L, when, new NullPointerException())));
            return this;
        }
    }

    private static ScriptState stateOf(Runner runner) {
        return ClientRows.stateOf(runner.runner(), NOW, System.nanoTime(), ZONE);
    }

    @Test
    void aLiveRunningRunner_isRunning_forAsLongAsItsCurrentRun() {
        Runner r = Runner.started(STARTED, true);

        assertEquals(new ScriptState.Running(RUN_FOR), stateOf(r));
    }

    @Test
    void aRunningRunnerTheWatchdogFlagged_isStalled_forItsTimeInOnLoop() {
        Runner r = Runner.started(STARTED, true);
        r.liveness().enterLoop();
        r.liveness().markStalled();
        long later = System.nanoTime() + TimeUnit.SECONDS.toNanos(STALL_SECONDS);

        ScriptState state = ClientRows.stateOf(r.runner(), NOW, later, ZONE);

        ScriptState.Stalled stalled = switch (state) {
            case ScriptState.Stalled s -> s;
            default -> throw new AssertionError("expected stalled, got " + state);
        };
        assertEquals(STALL_SECONDS, stalled.inLoop().toSeconds());
    }

    @Test
    void aStoppedRunnerWhoseCurrentRunCrashed_isCrashed_withTheCrashAndItsTime() {
        Instant crashed = STARTED.plusSeconds(90);
        Runner r = Runner.started(STARTED, false).crashedAt(crashed);

        assertEquals(new ScriptState.Crashed("NullPointerException in onLoop()", LocalTime.of(14, 1, 30)),
                stateOf(r));
    }

    @Test
    void aStoppedRunnerWhoseCrashWasBeforeItsLastStart_isStopped() {
        Runner r = Runner.started(STARTED, false).crashedAt(STARTED.minusSeconds(1));

        assertEquals(new ScriptState.Stopped(), stateOf(r));
    }

    @Test
    void aRunnerThatNeverStarted_isStopped() {
        Runner r = Runner.started(null, false);

        assertEquals(new ScriptState.Stopped(), stateOf(r));
    }

    @Test
    void revokedAndAbandoned_areCutOff_evenWhileStillFlaggedRunning() {
        Runner revoked = Runner.started(STARTED, false);
        revoked.liveness().markRevoked();
        Runner abandoned = Runner.started(STARTED, true);
        abandoned.liveness().markAbandoned();
        Runner stalledThenRevoked = Runner.started(STARTED, true).crashedAt(NOW);
        stalledThenRevoked.liveness().markStalled();
        stalledThenRevoked.liveness().markRevoked();

        assertAll(
                () -> assertEquals(new ScriptState.CutOff(), stateOf(revoked)),
                () -> assertEquals(new ScriptState.CutOff(), stateOf(abandoned)),
                () -> assertEquals(new ScriptState.CutOff(), stateOf(stalledThenRevoked)));
    }

    @Test
    void aRunningRow_carriesItsLoopHistoryAndAverage() {
        Runner r = Runner.started(STARTED, true);
        r.profiler().recordLoop(LOOP_NANOS);
        r.profiler().recordLoop(LOOP_NANOS);

        ScriptRow row = ClientRows.rowOf(r.runner(), WOODCUTTING, NOW, System.nanoTime(), ZONE);

        assertAll(
                () -> assertEquals(WOODCUTTING, row.script()),
                () -> assertEquals(new ScriptState.Running(RUN_FOR), row.state()),
                () -> assertArrayEquals(new long[] {LOOP_NANOS, LOOP_NANOS}, row.laneNanos()),
                () -> assertEquals(142.0, row.avgLoopMs(), 1e-9));
    }

    @Test
    void aRowThatIsNotRunning_hasNoLane() {
        Runner r = Runner.started(STARTED, false);
        r.profiler().recordLoop(LOOP_NANOS);

        ScriptRow row = ClientRows.rowOf(r.runner(), WOODCUTTING, NOW, System.nanoTime(), ZONE);

        assertAll(
                () -> assertEquals(new ScriptState.Stopped(), row.state()),
                () -> assertEquals(0, row.laneNanos().length));
    }

    @Test
    void theLivenessCheckReadsTheRunnersOwnState() {
        Runner r = Runner.started(STARTED, true);
        when(r.runner().liveness()).thenReturn(Liveness.REVOKED);

        assertEquals(new ScriptState.CutOff(), stateOf(r));
    }
}
