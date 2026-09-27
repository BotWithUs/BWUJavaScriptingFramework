package com.botwithus.bot.cli.gui.preview;

import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.gui.pages.installed.LoadProblem;
import com.botwithus.bot.cli.gui.pages.management.ActivityRow;
import com.botwithus.bot.cli.gui.pages.management.ManagementModel;
import com.botwithus.bot.cli.gui.pages.management.ManagementRow;
import com.botwithus.bot.cli.gui.pages.management.ManagementView;
import com.botwithus.bot.cli.gui.pages.management.RunHealth;
import com.botwithus.bot.cli.gui.pages.management.RunState;
import com.botwithus.bot.cli.gui.pages.management.ScriptAbout;
import com.botwithus.bot.cli.gui.pages.management.TargetChange;
import com.botwithus.bot.cli.gui.pages.management.TargetChoices;
import com.botwithus.bot.cli.gui.pages.management.TargetRow;
import com.botwithus.bot.cli.management.Target;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * DEV ONLY. The Management page's fixtures: the design's four management
 * scripts (one managing a group and three client scripts, one the whole host,
 * one crashed, one not applied) and one JAR that failed to load, plus an empty
 * folder. Names, numbers and activity are made up. Actions do nothing: the
 * preview captures states, it does not play them.
 */
final class FixtureManagementModel implements ManagementModel {

    static final String BREAK_SCHEDULER = "Break Scheduler";
    static final String LOGIN_WATCHER = "Login Watcher";
    static final String RESTART_ON_CRASH = "Restart on Crash";
    static final String WORLD_BALANCER = "World Balancer";
    static final Path FAILED_JAR = Path.of("scripts", "management", "mule-coordinator-0.3.jar");
    static final Target QUESTERS = new Target.Group(GroupId.migratedFrom("Questers"));
    static final Target DIV_TEAM = new Target.Group(GroupId.migratedFrom("Div team"));

    private static final String FOLDER = "scripts/management/";
    private static final String OAKHEART_UUID = "3f9a1c2e-5b7d-4e10-a2c4-e6f8b0d2f4a6";
    private static final Target OAKHEART_WOODCUTTING = new Target.ClientScript(OAKHEART_UUID, "Woodcutting");
    private static final String ONE_CLIENT_SCRIPT = "one client's script";
    private static final int BREAK_FIELDS = 6;
    private static final int OWN_WOODCUTTERS = 2;
    private static final int OWN_OAKHEART = 2;
    private static final long BREAK_LOOPS = 2531;
    private static final long RESTART_LOOPS = 8060;
    private static final double BREAK_AVG_MS = 4.2;
    private static final double RESTART_AVG_MS = 1.1;
    private static final Duration BREAK_UP = Duration.ofMinutes(42).plusSeconds(10);
    private static final Duration RESTART_UP = Duration.ofHours(2).plusMinutes(14).plusSeconds(3);

    private ManagementView view = busy();

    @Override
    public ManagementView view() {
        return view;
    }

    /** The folder holds nothing at all. */
    void showEmpty() {
        view = new ManagementView(FOLDER, false, List.of(), List.of(), choices());
    }

    private static ManagementView busy() {
        return new ManagementView(FOLDER, false,
                List.of(breakScheduler(), loginWatcher(), restartOnCrash(), worldBalancer()),
                List.of(failedJar()), choices());
    }

    private static ManagementRow breakScheduler() {
        ScriptAbout about = new ScriptAbout(BREAK_SCHEDULER, "1.2", "Staggers breaks so a group's members don't"
                + " all play at once. Stops a member's script for the break and starts it again after.", "You",
                "com.example.management.BreakScheduler", BREAK_FIELDS, false);
        List<TargetRow> targets = List.of(
                new TargetRow(FixtureManagementSettings.WOODCUTTERS, "Woodcutters", "group · 4 clients",
                        OWN_WOODCUTTERS),
                new TargetRow(OAKHEART_WOODCUTTING, "Oakheart · Woodcutting", ONE_CLIENT_SCRIPT, OWN_OAKHEART),
                new TargetRow(FixtureManagementSettings.FERNMOSS_DIVINATION, "Fernmoss · Divination",
                        ONE_CLIENT_SCRIPT, 1),
                new TargetRow(FixtureManagementSettings.KESTREL_WALK, "Kestrel Moor · Walk to Flag",
                        ONE_CLIENT_SCRIPT, 0));
        RunHealth health = new RunHealth(new RunState.Running(BREAK_UP), OptionalDouble.of(BREAK_AVG_MS),
                BREAK_LOOPS, 0, Optional.empty());
        return new ManagementRow(about, targets, health, List.of(
                new ActivityRow("14:05", "stopScript", "Woodcutting on Duskwater", "done"),
                new ActivityRow("13:48", "startScript", "Woodcutting on Wrenfield", "done"),
                new ActivityRow("13:31", "startScript", "Divination on Brackenridge",
                        "refused: not in this script's targets"),
                new ActivityRow("13:28", "stopScript", "Woodcutting on Wrenfield", "done"),
                new ActivityRow("13:23", "scheduleScript", "Woodcutting on Duskwater in PT20M", "done")));
    }

    private static ManagementRow loginWatcher() {
        ScriptAbout about = new ScriptAbout(LOGIN_WATCHER, "1.0", "Logs a client back in when it drops to the"
                + " lobby.", "BotWithUs", "com.example.management.LoginWatcher", 2, false);
        return new ManagementRow(about, List.of(), new RunHealth(new RunState.Stopped(), OptionalDouble.empty(), 0,
                0, Optional.empty()), List.of());
    }

    private static ManagementRow restartOnCrash() {
        ScriptAbout about = new ScriptAbout(RESTART_ON_CRASH, "1.0", "Watches for ScriptCrashedEvent and restarts"
                + " that client's script, at most 3 times an hour per client.", "You",
                "com.example.management.RestartOnCrash", 2, true);
        List<TargetRow> targets = List.of(new TargetRow(Target.host(), "Whole host", "every connected client", 0));
        RunHealth health = new RunHealth(new RunState.Running(RESTART_UP), OptionalDouble.of(RESTART_AVG_MS),
                RESTART_LOOPS, 0, Optional.empty());
        return new ManagementRow(about, targets, health, List.of(
                new ActivityRow("14:02", "restartScript", "Cook's Assistant on Tamsin Vale", "done"),
                new ActivityRow("11:52", "startScriptOnAll", "Example Script on every client", "done")));
    }

    private static ManagementRow worldBalancer() {
        ScriptAbout about = new ScriptAbout(WORLD_BALANCER, "0.2", "Moves members off worlds that get crowded.",
                "You", "com.example.management.WorldBalancer", 1, false);
        List<TargetRow> targets = List.of(new TargetRow(QUESTERS, "Questers", "group · 3 clients", 0));
        String crash = "IllegalStateException in onStart()";
        RunHealth health = new RunHealth(new RunState.Crashed(crash), OptionalDouble.empty(), 0, 1,
                Optional.of(crash + " · 13:10"));
        return new ManagementRow(about, targets, health, List.of());
    }

    private static LoadProblem failedJar() {
        String trace = "java.util.ServiceConfigurationError: com.botwithus.bot.api.script.ManagementScript:\n"
                + "  Provider com.example.management.MuleCoordinator not found\n"
                + "\tat java.base/java.util.ServiceLoader.fail(ServiceLoader.java:593)\n"
                + "\tat com.botwithus.bot.core.runtime.ManagementScriptLoader.load(ManagementScriptLoader.java)";
        return new LoadProblem(LoadProblem.Kind.FAILED, FAILED_JAR,
                "ServiceConfigurationError: ManagementScript provider not found", trace, Optional.empty());
    }

    private static TargetChoices choices() {
        return new TargetChoices(
                List.of(new TargetChoices.Option(FixtureManagementSettings.WOODCUTTERS, "Woodcutters · 4 clients"),
                        new TargetChoices.Option(QUESTERS, "Questers · 3 clients"),
                        new TargetChoices.Option(DIV_TEAM, "Div team · 2 clients")),
                List.of(new TargetChoices.Option(OAKHEART_WOODCUTTING, "Oakheart · Woodcutting"),
                        new TargetChoices.Option(FixtureManagementSettings.FERNMOSS_DIVINATION,
                                "Fernmoss · Divination"),
                        new TargetChoices.Option(FixtureManagementSettings.KESTREL_WALK,
                                "Kestrel Moor · Walk to Flag"),
                        new TargetChoices.Option(new Target.ClientScript("5c20aa91-d8fc-4098-c2e4-a6b8d0f21ec6",
                                "Example Script"), "Quillon · Example Script")));
    }

    @Override
    public void reload() { }

    @Override
    public void openFolder() { }

    @Override
    public void stopAll() { }

    @Override
    public void start(String script) { }

    @Override
    public void stop(String script) { }

    @Override
    public void restart(String script) { }

    @Override
    public void changeTargets(String script, TargetChange change, boolean isRestart) { }

    @Override
    public void openSettings(String script, Optional<Target> target) { }

    @Override
    public void openScriptUi(String script) { }
}
