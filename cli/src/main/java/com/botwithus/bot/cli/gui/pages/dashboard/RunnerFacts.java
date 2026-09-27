package com.botwithus.bot.cli.gui.pages.dashboard;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Liveness;
import com.botwithus.bot.core.runtime.ScriptRunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** What a script runner's live state means for the Dashboard. */
final class RunnerFacts {

    private static final Logger log = LoggerFactory.getLogger(RunnerFacts.class);

    private RunnerFacts() {}

    /**
     * The runner's status. The watchdog's verdict comes first: a cut-off runner
     * can never run again and a stalled one is stuck whether or not a stop is
     * pending; only then does running, or a crash of the current run, decide.
     */
    static RunnerStatus status(ScriptRunner runner) {
        Liveness liveness = runner.liveness();
        if (liveness.isTerminal()) {
            return RunnerStatus.CUT_OFF;
        }
        if (liveness == Liveness.STALLED) {
            return RunnerStatus.STALLED;
        }
        if (runner.isRunning()) {
            return RunnerStatus.RUNNING;
        }
        return currentCrash(runner).isPresent() ? RunnerStatus.CRASHED : RunnerStatus.STOPPED;
    }

    /**
     * The crash that ended the runner's current run. A runner is reused across
     * restarts, so a crash from before the latest start belongs to an earlier
     * run and is not current; neither is any crash while the runner runs.
     */
    static Optional<LastCrash> currentCrash(ScriptRunner runner) {
        Optional<LastCrash> crash = runner.health().lastCrash();
        Instant started = runner.lastStartedAt();
        if (runner.isRunning() || crash.isEmpty() || started == null || crash.get().when().isBefore(started)) {
            return Optional.empty();
        }
        return crash;
    }

    /**
     * Whether the inspector has anything to show for the runner: declared fields
     * or a script UI. Asks the script, so callers cache the answer per runner. A
     * script that throws while answering gets the benefit of the doubt: its
     * Settings button stays enabled and the inspector reports the failure.
     */
    static boolean hasSettings(ScriptRunner runner) {
        try {
            List<ConfigField> fields = runner.getConfigFields();
            BotScript script = runner.getScript();
            return (fields != null && !fields.isEmpty()) || (script != null && script.getUI() != null);
        } catch (RuntimeException e) {
            log.debug("{} failed to say whether it has settings: {}", runner.getScriptName(), e.toString());
            return true;
        }
    }
}
