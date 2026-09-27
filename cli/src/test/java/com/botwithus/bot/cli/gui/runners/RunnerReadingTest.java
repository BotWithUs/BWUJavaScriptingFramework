package com.botwithus.bot.cli.gui.runners;

import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Liveness;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.api.runtime.ScriptHealth;
import com.botwithus.bot.core.runtime.ScriptRunner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The one rule every page uses: cut off, then stalled, then running, then a crash of this run, then stopped. */
class RunnerReadingTest {

    private static final Instant STARTED = Instant.parse("2026-09-26T10:00:00Z");
    private static final Optional<LastCrash> NO_CRASH = Optional.empty();

    private static RunnerReading reading(boolean isRunning, Liveness liveness, Instant startedAt,
                                         Optional<LastCrash> crash) {
        return new RunnerReading(isRunning, liveness, Optional.ofNullable(startedAt), crash);
    }

    private static Optional<LastCrash> crashAt(Instant when) {
        return Optional.of(new LastCrash(Phase.ON_LOOP, 0L, when, new NullPointerException()));
    }

    @ParameterizedTest
    @EnumSource(value = Liveness.class, names = {"REVOKED", "ABANDONED"})
    void cutOff_winsOverEverything(Liveness terminal) {
        assertAll(
                () -> assertEquals(RunnerStatus.CUT_OFF, reading(true, terminal, STARTED, NO_CRASH).status()),
                () -> assertEquals(RunnerStatus.CUT_OFF,
                        reading(false, terminal, STARTED, crashAt(STARTED.plusSeconds(1))).status()));
    }

    @Test
    void stalled_winsOverRunning_andOverAPendingStop() {
        assertAll(
                () -> assertEquals(RunnerStatus.STALLED, reading(true, Liveness.STALLED, STARTED, NO_CRASH).status()),
                () -> assertEquals(RunnerStatus.STALLED,
                        reading(false, Liveness.STALLED, STARTED, crashAt(STARTED.plusSeconds(1))).status()));
    }

    @Test
    void running_winsOverAnyCrash() {
        assertEquals(RunnerStatus.RUNNING,
                reading(true, Liveness.LIVE, STARTED, crashAt(STARTED.plusSeconds(1))).status());
    }

    @Test
    void aCrashSinceTheLastStart_isCrashed_andIsTheCurrentCrash() {
        RunnerReading crashed = reading(false, Liveness.LIVE, STARTED, crashAt(STARTED.plusSeconds(5)));

        assertAll(
                () -> assertEquals(RunnerStatus.CRASHED, crashed.status()),
                () -> assertEquals(Optional.of(STARTED.plusSeconds(5)), crashed.currentCrash().map(LastCrash::when)));
    }

    @Test
    void aCrashFromBeforeTheLastStart_isJustStopped() {
        RunnerReading restarted = reading(false, Liveness.LIVE, STARTED, crashAt(STARTED.minusSeconds(5)));

        assertAll(
                () -> assertEquals(RunnerStatus.STOPPED, restarted.status()),
                () -> assertEquals(Optional.empty(), restarted.currentCrash()));
    }

    @Test
    void aRunnerThatNeverStarted_isStopped_andUnused() {
        RunnerReading registered = reading(false, Liveness.LIVE, null, NO_CRASH);

        assertAll(
                () -> assertEquals(RunnerStatus.STOPPED, registered.status()),
                () -> assertFalse(registered.hasBeenStarted()),
                () -> assertTrue(reading(false, Liveness.LIVE, STARTED, NO_CRASH).hasBeenStarted()),
                () -> assertTrue(reading(true, Liveness.LIVE, null, NO_CRASH).hasBeenStarted()));
    }

    @Test
    void of_readsTheRunner_andItsCrashOnlyWhenItCanBeCurrent() {
        ScriptRunner stopped = mock(ScriptRunner.class);
        when(stopped.isRunning()).thenReturn(false);
        when(stopped.liveness()).thenReturn(Liveness.LIVE);
        when(stopped.lastStartedAt()).thenReturn(STARTED);
        when(stopped.health()).thenReturn(ScriptHealth.HEALTHY.withCrash(crashAt(STARTED.plusSeconds(2)).get()));
        ScriptRunner running = mock(ScriptRunner.class);
        when(running.isRunning()).thenReturn(true);
        when(running.liveness()).thenReturn(Liveness.LIVE);

        assertAll(
                () -> assertEquals(RunnerStatus.CRASHED, RunnerReading.of(stopped).status()),
                () -> assertEquals(RunnerStatus.RUNNING, RunnerReading.of(running).status()));
        verify(running, never()).health();
    }

    @Test
    void problemAndActive_sortTheStatuses() {
        assertAll(
                () -> assertTrue(RunnerStatus.STALLED.isProblem() && RunnerStatus.STALLED.isActive()),
                () -> assertTrue(RunnerStatus.CRASHED.isProblem() && !RunnerStatus.CRASHED.isActive()),
                () -> assertTrue(RunnerStatus.CUT_OFF.isProblem() && !RunnerStatus.CUT_OFF.isActive()),
                () -> assertTrue(!RunnerStatus.RUNNING.isProblem() && RunnerStatus.RUNNING.isActive()),
                () -> assertTrue(!RunnerStatus.STOPPED.isProblem() && !RunnerStatus.STOPPED.isActive()));
    }
}
