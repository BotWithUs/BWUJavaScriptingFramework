package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.cli.gui.runners.CrashText;
import com.botwithus.bot.cli.gui.runners.RunnerReading;
import com.botwithus.bot.cli.gui.runners.RunnerStatus;
import com.botwithus.bot.core.runtime.ScriptRunner;

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
        RunnerReading reading = RunnerReading.of(runner);
        RunnerStatus status = reading.status();
        if (!reading.hasBeenStarted() && !status.isProblem()) {
            return Optional.empty();
        }
        String name = runner.getScriptName();
        return Optional.of(switch (status) {
            case CUT_OFF -> new ScriptFact(name, ScriptState.CUT_OFF, OptionalDouble.empty(), "would not stop");
            case STALLED -> new ScriptFact(name, ScriptState.STALLED, avgLoopMs(runner), "stuck in onLoop()");
            case RUNNING -> new ScriptFact(name, ScriptState.RUNNING, avgLoopMs(runner), "");
            case CRASHED -> new ScriptFact(name, ScriptState.CRASHED, OptionalDouble.empty(),
                    reading.currentCrash().map(CrashText::summary).orElse(""));
            case STOPPED -> ScriptFact.of(name, ScriptState.STOPPED);
        });
    }

    private static OptionalDouble avgLoopMs(ScriptRunner runner) {
        return OptionalDouble.of(runner.getProfiler().avgLoopMs());
    }

}
