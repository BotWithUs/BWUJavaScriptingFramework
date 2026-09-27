package com.botwithus.bot.cli.management;

import com.botwithus.bot.api.script.ClientOrchestrator;
import com.botwithus.bot.api.script.ClientOrchestrator.OpResult;
import com.botwithus.bot.cli.ClientManager;
import com.botwithus.bot.cli.groups.ClientGroup;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.groups.GroupStore;
import com.botwithus.bot.core.runtime.ManagementScriptRunner;
import com.botwithus.bot.core.runtime.ManagementScriptRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * What the user does to management scripts from the host, as opposed to what
 * the scripts do through their orchestrators: start and stop them, restart one
 * after its targets change, and stop everything on a group without its manager
 * starting it again.
 */
public final class ManagementControl {

    private final Supplier<ManagementScriptRuntime> runtime;
    private final ManagementTargets targets;
    private final GroupStore groups;
    private final ClientOrchestrator host;

    /**
     * @param runtime the management runtime, created on first use
     * @param targets where each script's targets and wanted state are kept
     * @param groups  the host's groups
     * @param host    the host's own orchestrator, over every client
     */
    public ManagementControl(Supplier<ManagementScriptRuntime> runtime, ManagementTargets targets,
                             GroupStore groups, ClientOrchestrator host) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.groups = Objects.requireNonNull(groups, "groups");
        this.host = Objects.requireNonNull(host, "host");
    }

    /**
     * Starts {@code script} and remembers that it should run.
     *
     * @return {@code false} if no such script is loaded
     */
    public boolean start(String script) {
        Optional<ManagementScriptRunner> runner = runnerOf(script);
        if (runner.isEmpty()) {
            return false;
        }
        targets.setDesiredRunning(script, true);
        runner.get().start();
        return true;
    }

    /**
     * Stops {@code script} and remembers that it should not run.
     *
     * @return {@code false} if it was not running
     */
    public boolean stop(String script) {
        targets.setDesiredRunning(script, false);
        return runtime.get().stopScript(script);
    }

    /**
     * Restarts {@code script} if it is running, so it starts over with its
     * targets as they are now. The orchestrator applies a target change at once
     * either way; the restart is for a script that read its clients in
     * {@code onStart}. Ask the user first: it interrupts what the script is doing.
     *
     * @return whether it was running and was restarted
     */
    public boolean restartIfRunning(String script) {
        Optional<ManagementScriptRunner> runner = runnerOf(script).filter(ManagementScriptRunner::isRunning);
        if (runner.isEmpty()) {
            return false;
        }
        runner.get().stop();
        runner.get().awaitStop(ClientManager.RESTART_STOP_TIMEOUT_MS);
        runner.get().start();
        return true;
    }

    /**
     * Stops every script on the group's clients, pausing the group's manager
     * first so it does not start them again. Client scripts elsewhere, and the
     * manager's work on its other targets, carry on.
     *
     * @return one result per client, as {@link ClientOrchestrator#stopAllScriptsOnGroup}
     *         gives them; empty if there is no such group
     */
    public List<OpResult> stopAllOnGroup(GroupId id) {
        Optional<ClientGroup> group = groups.get(id);
        if (group.isEmpty()) {
            return List.of();
        }
        targets.setManagerPaused(id, true);
        return host.stopAllScriptsOnGroup(group.get().name());
    }

    /**
     * Lets the group's manager start scripts on it again.
     *
     * @return {@code false} if the group has no manager or it was not paused
     */
    public boolean resumeManager(GroupId id) {
        return targets.setManagerPaused(id, false);
    }

    /**
     * Starts each loaded script that should be running and is not, as after a
     * host restart.
     *
     * @return the scripts started
     */
    public List<String> startWanted() {
        List<String> started = new ArrayList<>();
        for (ManagementScriptRunner runner : runtime.get().getRunners()) {
            String name = runner.getScriptName();
            if (!runner.isRunning() && !runner.isDisposed() && targets.isDesiredRunning(name)) {
                runner.start();
                started.add(name);
            }
        }
        return List.copyOf(started);
    }

    private Optional<ManagementScriptRunner> runnerOf(String script) {
        return Optional.ofNullable(runtime.get().findRunner(script));
    }
}
