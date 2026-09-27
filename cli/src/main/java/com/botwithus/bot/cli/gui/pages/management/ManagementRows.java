package com.botwithus.bot.cli.gui.pages.management;

import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.ScriptHealth;
import com.botwithus.bot.cli.groups.ClientGroup;
import com.botwithus.bot.cli.groups.GroupStore;
import com.botwithus.bot.cli.gui.inspector.ScriptCode;
import com.botwithus.bot.cli.gui.runners.CrashText;
import com.botwithus.bot.cli.management.ManagementSettings;
import com.botwithus.bot.cli.management.ManagementTargets;
import com.botwithus.bot.cli.management.OrchestratorAuditLog;
import com.botwithus.bot.cli.management.Target;
import com.botwithus.bot.cli.management.TargetLabels;
import com.botwithus.bot.core.runtime.ManagementScriptRunner;
import com.botwithus.bot.core.runtime.ScriptProfiler;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Builds the page's rows from the host as it is now: one per registered
 * management script, with its targets, its runner's state and health, and its
 * orchestrator calls.
 */
final class ManagementRows {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final String EVERY_CLIENT = "every connected client";
    private static final String ONE_CLIENT_SCRIPT = "one client's script";
    private static final String GROUP = "group";

    /**
     * What the rows are built from.
     *
     * @param abouts what each runner's script says about itself, kept between builds
     *               because it calls into script code
     */
    record Sources(ManagementTargets targets, ManagementSettings settings, TargetLabels labels, GroupStore groups,
                   OrchestratorAuditLog audit, Map<ManagementScriptRunner, ScriptAbout> abouts) {

        Sources {
            Objects.requireNonNull(targets, "targets");
            Objects.requireNonNull(settings, "settings");
            Objects.requireNonNull(labels, "labels");
            Objects.requireNonNull(groups, "groups");
            Objects.requireNonNull(audit, "audit");
            Objects.requireNonNull(abouts, "abouts");
        }
    }

    private final Sources in;
    private final Instant now;
    private final ZoneId zone;

    ManagementRows(Sources in, Instant now, ZoneId zone) {
        this.in = in;
        this.now = now;
        this.zone = zone;
    }

    /** One row per runner, by name. */
    List<ManagementRow> rows(List<ManagementScriptRunner> runners) {
        List<ManagementRow> rows = new ArrayList<>();
        for (ManagementScriptRunner runner : runners) {
            rows.add(row(runner));
        }
        rows.sort(Comparator.comparing(ManagementRow::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(rows);
    }

    private ManagementRow row(ManagementScriptRunner runner) {
        String name = runner.getScriptName();
        ScriptAbout about = in.abouts().computeIfAbsent(runner, ManagementRows::aboutOf);
        return new ManagementRow(about, targetRows(name), health(runner), activity(name));
    }

    /** The script's targets as the page names them, in the order the script lists them. */
    List<TargetRow> targetRows(String script) {
        List<TargetRow> rows = new ArrayList<>();
        for (Target target : in.targets().targetsOf(script)) {
            int own = switch (target) {
                case Target.Host _ -> 0;
                case Target.Group _, Target.ClientScript _ -> in.settings().ownCount(script, target);
            };
            rows.add(new TargetRow(target, in.labels().of(target), subOf(target), own));
        }
        return rows;
    }

    private String subOf(Target target) {
        return switch (target) {
            case Target.Host _ -> EVERY_CLIENT;
            case Target.Group group -> in.groups().get(group.id())
                    .map(found -> GROUP + " · " + ManagementText.count(found.members().size(), "client"))
                    .orElse(GROUP);
            case Target.ClientScript _ -> ONE_CLIENT_SCRIPT;
        };
    }

    private RunHealth health(ManagementScriptRunner runner) {
        ScriptProfiler profiler = runner.getProfiler();
        ScriptHealth health = runner.health();
        long loops = profiler.getLoopCount();
        OptionalDouble avg = loops > 0 ? OptionalDouble.of(profiler.avgLoopMs()) : OptionalDouble.empty();
        Optional<String> lastCrash = health.lastCrash().map(this::crashLine);
        return new RunHealth(stateOf(runner), avg, loops, health.totalCrashes(), lastCrash);
    }

    /** Running while its runner runs; crashed if its current run ended in a crash; else stopped. */
    private RunState stateOf(ManagementScriptRunner runner) {
        Instant started = runner.lastStartedAt();
        if (runner.isRunning()) {
            Duration up = started == null || now.isBefore(started) ? Duration.ZERO : Duration.between(started, now);
            return new RunState.Running(up);
        }
        return runner.health().lastCrash()
                .filter(crash -> started == null || !crash.when().isBefore(started))
                .<RunState>map(crash -> new RunState.Crashed(CrashText.summary(crash)))
                .orElseGet(RunState.Stopped::new);
    }

    /** "IllegalStateException in onStart() · 13:10". */
    private String crashLine(LastCrash crash) {
        return CrashText.summary(crash) + " · " + CLOCK.format(LocalTime.ofInstant(crash.when(), zone));
    }

    /** The script's orchestrator calls, newest first. */
    private List<ActivityRow> activity(String script) {
        List<OrchestratorAuditLog.Entry> entries = in.audit().entries(script);
        List<ActivityRow> rows = new ArrayList<>(entries.size());
        for (int i = entries.size() - 1; i >= 0; i--) {
            OrchestratorAuditLog.Entry e = entries.get(i);
            rows.add(new ActivityRow(CLOCK.format(LocalTime.ofInstant(e.at(), zone)), e.call(), e.target(),
                    e.result()));
        }
        return rows;
    }

    /** Every group, as the add-target row offers it: "Woodcutters · 4 clients". */
    List<TargetChoices.Option> groupChoices() {
        List<TargetChoices.Option> options = new ArrayList<>();
        for (ClientGroup group : in.groups().all()) {
            options.add(new TargetChoices.Option(new Target.Group(group.id()),
                    group.name() + " · " + ManagementText.count(group.members().size(), "client")));
        }
        return options;
    }

    /** What the script says about itself; script code, so a throw reads as none. */
    static ScriptAbout aboutOf(ManagementScriptRunner runner) {
        String name = runner.getScriptName();
        ScriptManifest m = runner.getManifest();
        int fields = ScriptCode.fields(runner::getConfigFields, name).size();
        boolean hasUi = ScriptCode.ui(() -> runner.getScript().getUI(), name) != null;
        String className = runner.getScript().getClass().getName();
        if (m == null) {
            return new ScriptAbout(name, "", "", "", className, fields, hasUi);
        }
        return new ScriptAbout(name, m.version(), m.description(), m.author(), className, fields, hasUi);
    }
}
