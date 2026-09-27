package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Liveness;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.core.runtime.ScriptRunner;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/** Reads what a client's script runners are doing, for a member's row. */
final class RunnerFacts {

    private RunnerFacts() {
    }

    /**
     * One fact per runner that has run on the client, in runtime order. A runner
     * registered but never started, as the runtime registers every installed
     * script when it needs one, says nothing about the client and is left out.
     */
    static List<ScriptFact> of(List<ScriptRunner> runners) {
        List<ScriptFact> facts = new ArrayList<>();
        for (ScriptRunner runner : runners) {
            factOf(runner).ifPresent(facts::add);
        }
        return List.copyOf(facts);
    }

    private static Optional<ScriptFact> factOf(ScriptRunner runner) {
        String name = runner.getScriptName();
        Liveness liveness = runner.liveness();
        // Cut off wins over everything: a runner that ignored a stop can still look busy.
        if (liveness.isTerminal()) {
            return Optional.of(new ScriptFact(name, ScriptState.CUT_OFF, OptionalDouble.empty(),
                    "would not stop"));
        }
        if (runner.isRunning()) {
            ScriptState state = liveness == Liveness.STALLED ? ScriptState.STALLED : ScriptState.RUNNING;
            String detail = state == ScriptState.STALLED ? "stuck in onLoop()" : "";
            return Optional.of(new ScriptFact(name, state, OptionalDouble.of(runner.getProfiler().avgLoopMs()),
                    detail));
        }
        Instant started = runner.lastStartedAt();
        if (started == null) {
            return Optional.empty();
        }
        Optional<LastCrash> crash = runner.health().lastCrash().filter(c -> !c.when().isBefore(started));
        return Optional.of(crash
                .map(c -> new ScriptFact(name, ScriptState.CRASHED, OptionalDouble.empty(), crashSummary(c)))
                .orElseGet(() -> ScriptFact.of(name, ScriptState.STOPPED)));
    }

    /** "NullPointerException in onLoop()". */
    static String crashSummary(LastCrash crash) {
        String type = crash.cause() != null ? crash.cause().getClass().getSimpleName() : "Error";
        return type + " in " + methodOf(crash.phase());
    }

    private static String methodOf(Phase phase) {
        return switch (phase) {
            case ON_START -> "onStart()";
            case ON_LOOP -> "onLoop()";
            case ON_STOP -> "onStop()";
            case ON_CONFIG_UPDATE -> "onConfigUpdate()";
        };
    }
}
