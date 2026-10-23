package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.script.ManagementContext;
import com.botwithus.bot.api.script.ManagementScript;
import com.botwithus.bot.api.ui.ScriptUI;
import com.botwithus.bot.core.runtime.ManagementScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRunner;

import java.time.Instant;
import java.util.List;

/**
 * Real scripts and real runners for the inspector tests. The runners are never
 * started and never applied to, so nothing here touches a pipe or the config
 * store on disk.
 */
final class TestScripts {

    static final String PIPE = "BotWithUs_14208";
    static final Instant T0 = Instant.parse("2026-09-26T10:00:00Z");
    static final ScriptUI NO_OP_UI = () -> { };

    private TestScripts() {}

    /** A client script with one field and no UI of its own. */
    @ScriptManifest(name = "Woodcutting", version = "2.0", author = "BotWithUs", category = ScriptCategory.WOODCUTTING)
    static final class Woodcutting extends QuietScript {
        @Override
        public List<ConfigField> getConfigFields() {
            return List.of(ConfigField.intField("stopValue", "Stop value", 90));
        }
    }

    /** A client script that draws its own UI and declares no fields. */
    @ScriptManifest(name = "Example Script")
    static final class UiOnly extends QuietScript {
        @Override
        public ScriptUI getUI() {
            return NO_OP_UI;
        }
    }

    /** A management script with one field of every type, and no UI of its own. */
    @ScriptManifest(name = "Break Scheduler", version = "1.2")
    static final class BreakScheduler extends QuietManagementScript {
        @Override
        public List<ConfigField> getConfigFields() {
            return List.of(
                    ConfigField.intField("breakEvery", "Break every (min)", 90),
                    ConfigField.boolField("logOut", "Log out during breaks", true),
                    ConfigField.choiceField("jitter", "Jitter", List.of("None", "Light", "Heavy"), "Light"),
                    ConfigField.stringField("quietHours", "Quiet hours", "23:00-07:00"),
                    ConfigField.itemIdField("bankItem", "Keep in bank", 995));
        }
    }

    /** A management script that draws its own UI and declares no fields. */
    @ScriptManifest(name = "Fleet Monitor", version = "0.4")
    static final class FleetMonitor extends QuietManagementScript {
        @Override
        public ScriptUI getUI() {
            return NO_OP_UI;
        }
    }

    /** A management script whose fields throw, as buggy script code may. */
    @ScriptManifest(name = "Broken")
    static final class Broken extends QuietManagementScript {
        @Override
        public List<ConfigField> getConfigFields() {
            throw new IllegalStateException("script bug");
        }
    }

    /** A runner for {@code script} on {@link #PIPE}, as the connection's runtime registers it. */
    static ScriptRunner clientRunner(BotScript script) {
        ScriptRunner runner = new ScriptRunner(script, null);
        runner.setConnectionName(PIPE);
        return runner;
    }

    static ManagementScriptRunner managementRunner(ManagementScript script) {
        return new ManagementScriptRunner(script, null);
    }

    /** Does nothing; subclasses add only what the test is about. */
    abstract static class QuietScript implements BotScript {
        @Override
        public void onStart(ScriptContext ctx) {
        }

        @Override
        public int onLoop() {
            return -1;
        }

        @Override
        public void onStop() {
        }
    }

    /** Does nothing; subclasses add only what the test is about. */
    abstract static class QuietManagementScript implements ManagementScript {
        @Override
        public void onStart(ManagementContext ctx) {
        }

        @Override
        public int onLoop() {
            return -1;
        }

        @Override
        public void onStop() {
        }
    }
}
