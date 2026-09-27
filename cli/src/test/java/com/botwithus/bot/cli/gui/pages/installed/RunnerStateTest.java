package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Liveness;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.cli.gui.runners.RunnerReading;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One client's dot in a script's row, derived from what its runner reports. */
class RunnerStateTest {

    private static final Instant STARTED = Instant.parse("2026-09-26T10:00:00Z");
    private static final Optional<LastCrash> NO_CRASH = Optional.empty();

    private static RunnerFacts facts(boolean isAlive, boolean isRunning, Liveness liveness,
                                     Instant startedAt, Optional<LastCrash> crash) {
        return new RunnerFacts(isAlive, new RunnerReading(isRunning, liveness, Optional.ofNullable(startedAt), crash));
    }

    private static Optional<LastCrash> crashAt(Instant when) {
        return Optional.of(new LastCrash(Phase.ON_LOOP, 0L, when, new NullPointerException()));
    }

    @Test
    void aRunnerLoopingNormally_isRunning() {
        assertEquals(RunnerState.RUNNING, RunnerState.of(facts(true, true, Liveness.LIVE, STARTED, NO_CRASH)));
    }

    @Test
    void aRunnerStuckInOnLoop_isStalled() {
        assertEquals(RunnerState.STALLED, RunnerState.of(facts(true, true, Liveness.STALLED, STARTED, NO_CRASH)));
    }

    @Test
    void aRunnerStillStuckInOnLoopAfterAStopWasAsked_isStalled_notStopped() {
        assertEquals(RunnerState.STALLED, RunnerState.of(facts(true, false, Liveness.STALLED, STARTED, NO_CRASH)));
    }

    @Test
    void aClientThatIsNotConnected_winsOverWhateverItsRunnerLastSaid() {
        assertEquals(RunnerState.OFFLINE, RunnerState.of(facts(false, true, Liveness.LIVE, STARTED, NO_CRASH)));
        assertEquals(RunnerState.OFFLINE,
                RunnerState.of(facts(false, false, Liveness.LIVE, STARTED, crashAt(STARTED.plusSeconds(1)))));
    }

    @Test
    void aCrashInTheCurrentRun_isCrashed() {
        assertEquals(RunnerState.CRASHED,
                RunnerState.of(facts(true, false, Liveness.LIVE, STARTED, crashAt(STARTED.plusSeconds(5)))));
    }

    @Test
    void aCrashFromBeforeTheLastStart_isJustStopped() {
        assertEquals(RunnerState.STOPPED,
                RunnerState.of(facts(true, false, Liveness.LIVE, STARTED, crashAt(STARTED.minusSeconds(5)))));
    }

    @Test
    void aRunnerTheRuntimeGaveUpOn_isCutOff_runningOrNot() {
        assertEquals(RunnerState.CUT_OFF, RunnerState.of(facts(true, false, Liveness.REVOKED, STARTED, NO_CRASH)));
        assertEquals(RunnerState.CUT_OFF, RunnerState.of(facts(true, true, Liveness.ABANDONED, STARTED, NO_CRASH)));
    }

    @Test
    void aStoppedRunner_isStopped() {
        assertEquals(RunnerState.STOPPED, RunnerState.of(facts(true, false, Liveness.LIVE, STARTED, NO_CRASH)));
    }

    @Test
    void onlyARunnerThatRanOrRuns_putsItsClientInTheRow() {
        assertFalse(facts(true, false, Liveness.LIVE, null, NO_CRASH).hasBeenUsed());
        assertTrue(facts(true, false, Liveness.LIVE, STARTED, NO_CRASH).hasBeenUsed());
    }

    @Test
    void activeMeansOn_problemMeansNeedsALook() {
        assertTrue(RunnerState.RUNNING.isActive());
        assertTrue(RunnerState.STALLED.isActive());
        assertFalse(RunnerState.CRASHED.isActive());
        assertFalse(RunnerState.OFFLINE.isActive());

        assertTrue(RunnerState.STALLED.isProblem());
        assertTrue(RunnerState.CRASHED.isProblem());
        assertTrue(RunnerState.CUT_OFF.isProblem());
        assertFalse(RunnerState.RUNNING.isProblem());
        assertFalse(RunnerState.STOPPED.isProblem());
        assertFalse(RunnerState.OFFLINE.isProblem());
    }
}
