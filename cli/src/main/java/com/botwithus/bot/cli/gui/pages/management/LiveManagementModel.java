package com.botwithus.bot.cli.gui.pages.management;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.gui.inspector.InspectorRequest;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject;
import com.botwithus.bot.cli.gui.inspector.InspectorTab;
import com.botwithus.bot.cli.gui.pages.installed.LoadProblems;
import com.botwithus.bot.cli.management.ManagementControl;
import com.botwithus.bot.cli.management.ManagementTargets;
import com.botwithus.bot.cli.management.Target;
import com.botwithus.bot.cli.management.TargetLabels;
import com.botwithus.bot.core.runtime.ManagementScriptRunner;
import com.botwithus.bot.core.runtime.ManagementScriptRuntime;
import com.botwithus.bot.core.runtime.ScriptFolder;
import com.botwithus.bot.core.runtime.ScriptRunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * {@link ManagementModel} over the real host: the management runtime's
 * runners, each script's targets and per-target settings, the orchestrator
 * audit log and the failed-load list.
 *
 * <p>Nothing here blocks the render thread. Reloading, starting, stopping and
 * changing targets run on the host's command executor; the view answers from
 * what the host says now. It is rebuilt at most once per {@code maxAge}, and
 * at once after an action, so the sidebar badge and the page share one build
 * per frame.</p>
 */
public final class LiveManagementModel implements ManagementModel {

    private static final Logger log = LoggerFactory.getLogger(LiveManagementModel.class);

    /**
     * What the model reads and where it sends work.
     *
     * @param inspector     opens the shared config inspector
     * @param executor      runs everything that loads, starts, stops or restarts a script
     * @param folderOpener  shows a folder in the system file browser; called on the render thread
     * @param clock         measures uptimes; its zone formats times
     * @param managementDir the management folder
     * @param folderLabel   that folder as the page shows it, such as {@code "scripts/management/"}
     * @param maxAge        how long one built view is reused
     */
    public record Deps(CliContext ctx, Consumer<InspectorRequest> inspector, Executor executor,
                       Consumer<Path> folderOpener, Clock clock, Path managementDir, String folderLabel,
                       Duration maxAge) {}

    private final Deps deps;
    private final CliContext ctx;
    private final AtomicBoolean isReloading = new AtomicBoolean();
    /** Set from any thread when the host changed under the cached view. */
    private volatile boolean isStale = true;
    /** Render thread only: what each runner's script says about itself, dropped with its runner. */
    private final Map<ManagementScriptRunner, ScriptAbout> abouts = new IdentityHashMap<>();
    private ManagementView cached;
    private Instant cachedAt = Instant.MIN;

    public LiveManagementModel(Deps deps) {
        this.deps = deps;
        this.ctx = deps.ctx();
    }

    @Override
    public ManagementView view() {
        Instant now = deps.clock().instant();
        if (cached == null || isStale || !now.isBefore(cachedAt.plus(deps.maxAge()))) {
            isStale = false;
            cached = build(now);
            cachedAt = now;
        }
        return cached;
    }

    private ManagementView build(Instant now) {
        List<ManagementScriptRunner> runners = runners();
        abouts.keySet().retainAll(Set.copyOf(runners));
        ManagementRows rows = new ManagementRows(new ManagementRows.Sources(ctx.getManagementTargets(),
                ctx.getManagementSettings(), labels(), ctx.getGroupStore(), ctx.getOrchestratorAudit(), abouts),
                now, deps.clock().getZone());
        TargetChoices choices = new TargetChoices(rows.groupChoices(), clientScriptChoices());
        return new ManagementView(deps.folderLabel(), isLoading(), rows.rows(runners),
                LoadProblems.of(ctx.getLoadIssues().issues(ScriptFolder.MANAGEMENT), List.of(), deps.folderLabel()),
                choices);
    }

    /** A reload is running, or the host has not finished its first load pass yet. */
    private boolean isLoading() {
        return isReloading.get() || !ctx.hasLoadedManagement();
    }

    private List<ManagementScriptRunner> runners() {
        ManagementScriptRuntime runtime = ctx.getManagementRuntime();
        return runtime == null ? List.of() : List.copyOf(runtime.getRunners());
    }

    private TargetLabels labels() {
        return new TargetLabels(ctx.getGroupStore(), ctx::accountNameOf);
    }

    /** Every script on a connected client the host knows by account: "Oakheart · Woodcutting". */
    private List<TargetChoices.Option> clientScriptChoices() {
        TargetLabels labels = labels();
        Set<Target.ClientScript> seen = new LinkedHashSet<>();
        for (Connection conn : List.copyOf(ctx.getConnections())) {
            Optional<String> uuid = ctx.clientKeyOf(conn.getName()).accountUuid();
            if (uuid.isEmpty() || !conn.isAlive()) {
                continue;
            }
            for (ScriptRunner runner : conn.getRuntime().getRunners()) {
                seen.add(new Target.ClientScript(uuid.get(), runner.getScriptName()));
            }
        }
        List<TargetChoices.Option> options = new ArrayList<>();
        seen.forEach(target -> options.add(new TargetChoices.Option(target, labels.of(target))));
        return options;
    }

    // ── Actions ────────────────────────────────────────────────────────────

    /** Stopping every runner and loading JARs take seconds, so the reload runs on the executor. */
    @Override
    public void reload() {
        if (!isReloading.compareAndSet(false, true)) {
            return;
        }
        isStale = true;
        submit("reload", () -> {
            try {
                ctx.reloadManagementScripts(ctx.afterReloadSetting());
            } finally {
                isReloading.set(false);
            }
        });
    }

    @Override
    public void openFolder() {
        deps.folderOpener().accept(deps.managementDir());
    }

    @Override
    public void stopAll() {
        submit("stop all", () -> runners().stream()
                .filter(ManagementScriptRunner::isRunning)
                .map(ManagementScriptRunner::getScriptName)
                .forEach(control()::stop));
    }

    @Override
    public void start(String script) {
        submit("start " + script, () -> control().start(script));
    }

    @Override
    public void stop(String script) {
        submit("stop " + script, () -> control().stop(script));
    }

    @Override
    public void restart(String script) {
        submit("restart " + script, () -> control().restartIfRunning(script));
    }

    @Override
    public void changeTargets(String script, TargetChange change, boolean isRestart) {
        submit("targets of " + script, () -> {
            ManagementTargets targets = ctx.getManagementTargets();
            boolean isChanged = switch (change) {
                case TargetChange.Add add -> targets.add(script, add.target());
                case TargetChange.Remove remove -> targets.remove(script, remove.target());
            };
            if (isChanged && isRestart) {
                control().restartIfRunning(script);
            }
        });
    }

    @Override
    public void openSettings(String script, Optional<Target> target) {
        runner(script).ifPresent(runner -> deps.inspector().accept(target
                .map(t -> InspectorRequest.forManagementTarget(runner, t))
                .orElseGet(() -> new InspectorRequest(new InspectorSubject.ManagementScript(script),
                        InspectorTab.SETTINGS))));
    }

    @Override
    public void openScriptUi(String script) {
        runner(script).ifPresent(runner -> deps.inspector().accept(
                new InspectorRequest(new InspectorSubject.ManagementScript(script), InspectorTab.SCRIPT_UI)));
    }

    private Optional<ManagementScriptRunner> runner(String script) {
        ManagementScriptRuntime runtime = ctx.getManagementRuntime();
        return runtime == null ? Optional.empty() : Optional.ofNullable(runtime.findRunner(script));
    }

    private ManagementControl control() {
        return ctx.getManagementControl();
    }

    private void submit(String what, Runnable task) {
        isStale = true;
        deps.executor().execute(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.warn("Management: {} failed: {}", what, e.toString());
            } finally {
                isStale = true;
            }
        });
    }
}
