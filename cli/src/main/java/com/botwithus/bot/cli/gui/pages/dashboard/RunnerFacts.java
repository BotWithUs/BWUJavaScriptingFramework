package com.botwithus.bot.cli.gui.pages.dashboard;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.core.runtime.ScriptRunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Whether a script runner has settings the Dashboard can open; its status is {@code RunnerReading}'s. */
final class RunnerFacts {

    private static final Logger log = LoggerFactory.getLogger(RunnerFacts.class);

    private RunnerFacts() {}

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
