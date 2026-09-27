package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.groups.ClientGroup;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.groups.ManagerSlot;
import com.botwithus.bot.cli.gui.inspector.ScriptCode;
import com.botwithus.bot.cli.management.ManagedLinks;
import com.botwithus.bot.cli.management.ManagementTargets.ManagedBy;
import com.botwithus.bot.cli.management.OrchestratorAuditLog;
import com.botwithus.bot.cli.management.Target;
import com.botwithus.bot.core.runtime.ManagementScriptRunner;
import com.botwithus.bot.core.runtime.ManagementScriptRuntime;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads what the Groups page shows about management from the live host: each
 * group's manager and how it is doing, the management scripts it can assign,
 * and which members a management script also targets on their own. Every
 * source is safe to read on the render thread.
 */
final class LiveGroupManagers {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private final CliContext ctx;
    private final Clock clock;

    LiveGroupManagers(CliContext ctx, Clock clock) {
        this.ctx = ctx;
        this.clock = clock;
    }

    Optional<ManagerInfo> manager(GroupId id) {
        return ctx.getGroupStore().get(id).flatMap(ClientGroup::manager).map(this::infoOf);
    }

    private ManagerInfo infoOf(ManagerSlot slot) {
        Optional<ManagementScriptRunner> runner = runner(slot.script());
        String version = runner.map(ManagementScriptRunner::getManifest).map(ScriptManifest::version).orElse("");
        ManagerInfo.State state = stateOf(slot, runner);
        Optional<Duration> uptime = runner.filter(ManagementScriptRunner::isRunning)
                .map(ManagementScriptRunner::lastStartedAt)
                .map(started -> Duration.between(started, clock.instant()));
        boolean hasSettings = runner.map(r -> !ScriptCode.fields(r::getConfigFields, slot.script()).isEmpty())
                .orElse(false);
        return new ManagerInfo(slot.script(), version, state, uptime, lastAction(slot.script()), hasSettings);
    }

    private static ManagerInfo.State stateOf(ManagerSlot slot, Optional<ManagementScriptRunner> runner) {
        if (runner.isEmpty()) {
            return ManagerInfo.State.NOT_LOADED;
        }
        if (!slot.shouldRun()) {
            return ManagerInfo.State.PAUSED;
        }
        return runner.get().isRunning() ? ManagerInfo.State.MANAGING : ManagerInfo.State.STOPPED;
    }

    /** "14:05 stopScript · Woodcutting on Duskwater": the script's newest orchestrator call. */
    private Optional<String> lastAction(String script) {
        List<OrchestratorAuditLog.Entry> entries = ctx.getOrchestratorAudit().entries(script);
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        OrchestratorAuditLog.Entry last = entries.getLast();
        String at = CLOCK.format(LocalTime.ofInstant(last.at(), clock.getZone()));
        return Optional.of(at + " " + last.call() + (last.target().isEmpty() ? "" : " · " + last.target()));
    }

    /** The management scripts loaded now, by name, with the groups each already manages. */
    List<ManagerChoice> managers() {
        ManagementScriptRuntime runtime = ctx.getManagementRuntime();
        if (runtime == null) {
            return List.of();
        }
        return runtime.getRunners().stream()
                .filter(r -> !r.isDisposed())
                .sorted(Comparator.comparing(ManagementScriptRunner::getScriptName, String.CASE_INSENSITIVE_ORDER))
                .map(this::choiceOf)
                .toList();
    }

    private ManagerChoice choiceOf(ManagementScriptRunner runner) {
        String name = runner.getScriptName();
        ScriptManifest manifest = runner.getManifest();
        List<String> groups = ctx.getGroupStore().all().stream()
                .filter(g -> g.manager().filter(slot -> slot.script().equals(name)).isPresent())
                .map(ClientGroup::name)
                .toList();
        return new ManagerChoice(name, manifest != null ? manifest.version() : "",
                manifest != null ? manifest.description() : "", groups);
    }

    /** The link under a member whose {@code script} a management script names on its own. */
    Optional<MemberManagement> memberManagement(GroupId id, String uuid, String script) {
        Optional<ManagedBy> direct = ManagedLinks.of(ctx.getManagementTargets(), uuid, script).stream()
                .filter(by -> switch (by.via()) {
                    case Target.ClientScript _ -> true;
                    case Target.Group _, Target.Host _ -> false;
                })
                .findFirst();
        if (direct.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> groupManager = ctx.getGroupStore().get(id).flatMap(ClientGroup::manager)
                .map(ManagerSlot::script);
        String manager = direct.get().managementScript();
        int own = ctx.getManagementSettings().ownCount(manager, direct.get().via());
        return Optional.of(MemberManagement.of(groupManager, manager, own, script));
    }

    Optional<ManagementScriptRunner> runner(String script) {
        ManagementScriptRuntime runtime = ctx.getManagementRuntime();
        return runtime == null ? Optional.empty() : Optional.ofNullable(runtime.findRunner(script));
    }
}
