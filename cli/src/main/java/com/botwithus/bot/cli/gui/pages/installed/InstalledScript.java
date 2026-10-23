package com.botwithus.bot.cli.gui.pages.installed;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One row of the Installed scripts page: a script on disk (or a Store script
 * that was), and one runner per client that runs it.
 *
 * @param key          identifies the row across frames: the name a runtime registers the
 *                     script under, or {@code store:<class>} for a Store script that is not loaded
 * @param runs         one per client that runs or has run the script, in client order
 * @param registeredOn the clients whose runtime holds a runner for the script, used or not
 * @param managedBy    the management scripts that manage it on some client
 */
public record InstalledScript(String key, ScriptIdentity identity, Provenance provenance, List<ClientRun> runs,
                              Set<String> registeredOn, List<String> managedBy) {

    public InstalledScript {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(provenance, "provenance");
        runs = List.copyOf(runs);
        registeredOn = Set.copyOf(registeredOn);
        managedBy = List.copyOf(managedBy);
    }

    public String name() {
        return identity.name();
    }

    /** How many of its clients are in {@code state}. */
    public int count(RunnerState state) {
        return (int) runs.stream().filter(r -> r.state() == state).count();
    }

    /** Running or stalled somewhere. */
    public boolean isActive() {
        return runs.stream().anyMatch(r -> r.state().isActive());
    }

    /** Clients where it is running or stalled: what "Stop everywhere" stops. */
    public int activeCount() {
        return (int) runs.stream().filter(r -> r.state().isActive()).count();
    }

    /** Stalled, crashed or cut off somewhere. */
    public boolean hasProblem() {
        return runs.stream().anyMatch(r -> r.state().isProblem());
    }

    public RunSummary summary() {
        return RunSummary.of(runs);
    }

    /** Whether it can be started from here; a Store script that is not loaded must be installed again first. */
    public boolean isStartable() {
        return provenance.isLoaded();
    }
}
