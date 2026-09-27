package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.api.ScriptCategory;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Builds page rows for the tests of the pure parts: filters, summaries, Start-on. */
final class Rows {

    private Rows() {}

    static ClientRun run(String client, RunnerState state) {
        return new ClientRun(client, client, state, state.name(), false);
    }

    static InstalledScript local(String name, ScriptCategory category, String jar, ClientRun... runs) {
        return row(name, category, new Provenance(ScriptSource.LOCAL, true, Optional.of(Path.of("scripts", jar)),
                Optional.empty(), Optional.empty()), runs);
    }

    static InstalledScript store(String name, ScriptCategory category, ClientRun... runs) {
        return row(name, category, new Provenance(ScriptSource.STORE, true, Optional.empty(), Optional.empty(),
                Optional.empty()), runs);
    }

    static InstalledScript row(String name, ScriptCategory category, Provenance provenance, ClientRun... runs) {
        List<ClientRun> list = Arrays.asList(runs);
        Set<String> registered = Set.copyOf(list.stream().map(ClientRun::clientId).toList());
        return new InstalledScript(name, new ScriptIdentity(name, "1.0", "", "", category, 0, false), provenance,
                list, registered, List.of());
    }
}
