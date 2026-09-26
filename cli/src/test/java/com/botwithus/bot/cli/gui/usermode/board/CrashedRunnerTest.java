package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.api.runtime.ScriptHealth;
import com.botwithus.bot.core.runtime.ScriptRunner;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The selection behind both the crashed card and its Restart button. They must
 * mean the same runner, which is why there is one method for both.
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
        ScriptHealth health = crashedAt == null
                ? ScriptHealth.HEALTHY
                : ScriptHealth.HEALTHY.withCrash(
                        new LastCrash(Phase.ON_LOOP, 0L, crashedAt, new IllegalStateException()));
        when(r.health()).thenReturn(health);
        return r;
    }

    @Test
    void staleCrash_isIgnored_soCardAndRestartBothMeanTheCurrentCrash() {
        // A crashes at t1. B crashes at t3 (the newest crash on the client), then
        // is restarted at t4 and stops cleanly. B's crash is from an earlier run.
        ScriptRunner a = runner(at(0), at(1), false);
        ScriptRunner b = runner(at(4), at(3), false);

        assertEquals(Optional.of(a), LiveClientBoard.crashedRunner(List.of(a, b)));
        assertEquals(Optional.of(a), LiveClientBoard.crashedRunner(List.of(b, a)), "order does not matter");
    }

    @Test
    void newestCurrentCrash_wins() {
        ScriptRunner a = runner(at(0), at(1), false);
        ScriptRunner b = runner(at(2), at(3), false);

        assertEquals(Optional.of(b), LiveClientBoard.crashedRunner(List.of(a, b)));
    }

    @Test
    void aRunningRunner_isNeverTheCrashedOne() {
        ScriptRunner a = runner(at(0), at(1), true);

        assertTrue(LiveClientBoard.crashedRunner(List.of(a)).isEmpty());
    }

    @Test
    void neverStartedOrNeverCrashed_isNotCrashed() {
        ScriptRunner neverStarted = runner(null, at(1), false);
        ScriptRunner healthy = runner(at(0), null, false);

        assertTrue(LiveClientBoard.crashedRunner(List.of(neverStarted, healthy)).isEmpty());
    }
}
