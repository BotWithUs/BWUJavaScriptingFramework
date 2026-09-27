package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Liveness;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.api.runtime.ScriptHealth;
import com.botwithus.bot.core.runtime.ScriptProfiler;
import com.botwithus.bot.core.runtime.ScriptRunner;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A member's scripts, read from its client's runners: running, stalled, crashed, cut off or stopped. */
class RunnerFactsTest {

    private static final Instant T0 = Instant.parse("2026-09-26T10:00:00Z");
    private static final long LOOP_NANOS = 150_000_000L;
    private static final double LOOP_MS = 150.0;
    private static final double TOLERANCE = 0.001;

    private static ScriptRunner runner(String name, boolean isRunning, Liveness liveness, Instant startedAt,
                                       Instant crashedAt) {
        ScriptRunner r = mock(ScriptRunner.class);
        when(r.getScriptName()).thenReturn(name);
        when(r.isRunning()).thenReturn(isRunning);
        when(r.liveness()).thenReturn(liveness);
        when(r.lastStartedAt()).thenReturn(startedAt);
        ScriptProfiler profiler = new ScriptProfiler();
        profiler.recordLoop(LOOP_NANOS);
        when(r.getProfiler()).thenReturn(profiler);
        when(r.health()).thenReturn(crashedAt == null ? ScriptHealth.HEALTHY : ScriptHealth.HEALTHY.withCrash(
                new LastCrash(Phase.ON_LOOP, 0L, crashedAt, new NullPointerException())));
        return r;
    }

    @Test
    void eachRunnerState_becomesItsScriptState() {
        List<ScriptRunner> runners = List.of(
                runner("Running", true, Liveness.LIVE, T0, null),
                runner("Stalled", true, Liveness.STALLED, T0, null),
                runner("CutOff", false, Liveness.REVOKED, T0, null),
                runner("Crashed", false, Liveness.LIVE, T0, T0.plusSeconds(1)),
                runner("Stopped", false, Liveness.LIVE, T0, null));

        List<ScriptFact> facts = RunnerFacts.of(runners);

        assertAll(
                () -> assertEquals(List.of(ScriptState.RUNNING, ScriptState.STALLED, ScriptState.CUT_OFF,
                        ScriptState.CRASHED, ScriptState.STOPPED), facts.stream().map(ScriptFact::state).toList()),
                () -> assertEquals(LOOP_MS, facts.getFirst().avgLoopMs().orElseThrow(), TOLERANCE),
                () -> assertEquals("NullPointerException in onLoop()", facts.get(3).detail()));
    }

    @Test
    void aCrashFromAnEarlierRun_isNotACrashNow() {
        ScriptRunner restarted = runner("Restarted", false, Liveness.LIVE, T0.plusSeconds(2), T0.plusSeconds(1));

        assertEquals(ScriptState.STOPPED, RunnerFacts.of(List.of(restarted)).getFirst().state());
    }

    @Test
    void aRunnerThatNeverStarted_isLeftOut() {
        ScriptRunner registered = runner("Installed", false, Liveness.LIVE, null, null);

        assertEquals(List.of(), RunnerFacts.of(List.of(registered)));
    }
}
