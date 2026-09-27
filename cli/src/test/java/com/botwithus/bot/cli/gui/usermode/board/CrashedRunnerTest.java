package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.api.runtime.ScriptHealth;
import com.botwithus.bot.cli.gui.runners.RunnerReading;
import com.botwithus.bot.core.runtime.RunnerLiveness;
import com.botwithus.bot.core.runtime.ScriptRunner;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which crash a script row shows, and so what its Restart restarts. Each row
 * judges its own runner: a crash counts only if it ended the runner's current
 * run, and every runner whose current run crashed shows as crashed, not just
 * the newest one on the client.
 */
class CrashedRunnerTest {

    private static final Instant T0 = Instant.parse("2026-09-25T10:00:00Z");

    private static Instant at(int seconds) {
        return T0.plusSeconds(seconds);
    }

    private static ScriptRunner runner(Instant startedAt, Instant crashedAt, boolean running) {
        ScriptRunner r = mock(ScriptRunner.class);
        when(r.lastStartedAt()).thenReturn(startedAt);
        when(r.isRunning()).thenReturn(running);
        when(r.livenessState()).thenReturn(new RunnerLiveness());
        when(r.liveness()).thenReturn(new RunnerLiveness().get());
        ScriptHealth health = crashedAt == null
                ? ScriptHealth.HEALTHY
                : ScriptHealth.HEALTHY.withCrash(
                        new LastCrash(Phase.ON_LOOP, 0L, crashedAt, new IllegalStateException()));
        when(r.health()).thenReturn(health);
        return r;
    }

    /** The crash the row's Restart acts on: the shared rule's, as the row reads it. */
    private static Optional<LastCrash> currentCrash(ScriptRunner r) {
        return RunnerReading.of(r).currentCrash();
    }

    private static boolean showsCrashed(ScriptRunner r) {
        return switch (ClientRows.stateOf(r, at(10), System.nanoTime(), ZoneOffset.UTC)) {
            case ScriptState.Crashed _ -> true;
            default -> false;
        };
    }

    @Test
    void staleCrash_isIgnored_soTheRowAndItsRestartMeanTheCurrentRun() {
        // B crashed at t3, then was restarted at t4 and stopped cleanly: its
        // crash is from an earlier run.
        ScriptRunner b = runner(at(4), at(3), false);

        assertTrue(currentCrash(b).isEmpty());
        assertEquals(false, showsCrashed(b));
    }

    @Test
    void everyRunnerWhoseCurrentRunCrashed_showsCrashed_notOnlyTheNewest() {
        ScriptRunner a = runner(at(0), at(1), false);
        ScriptRunner b = runner(at(2), at(3), false);

        assertEquals(List.of(true, true), List.of(showsCrashed(a), showsCrashed(b)));
        assertEquals(Optional.of(at(1)), currentCrash(a).map(LastCrash::when));
        assertEquals(Optional.of(at(3)), currentCrash(b).map(LastCrash::when));
    }

    @Test
    void aRunningRunner_isNeverTheCrashedOne() {
        ScriptRunner a = runner(at(0), at(1), true);

        assertTrue(currentCrash(a).isEmpty());
    }

    @Test
    void neverStartedOrNeverCrashed_isNotCrashed() {
        ScriptRunner neverStarted = runner(null, at(1), false);
        ScriptRunner healthy = runner(at(0), null, false);

        assertTrue(currentCrash(neverStarted).isEmpty());
        assertTrue(currentCrash(healthy).isEmpty());
    }

    @Test
    void aCrashAtTheSameInstantAsTheStart_belongsToThatRun() {
        ScriptRunner r = runner(at(0), at(0), false);

        assertTrue(currentCrash(r).isPresent());
    }
}
