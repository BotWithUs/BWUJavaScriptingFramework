package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.ui.ScriptUI;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ClientScript;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ManagementScript;
import com.botwithus.bot.cli.gui.usermode.board.ScriptInfo;
import com.botwithus.bot.core.runtime.ManagementScriptRunner;
import com.botwithus.bot.core.runtime.ManagementScriptRuntime;
import com.botwithus.bot.core.runtime.ScriptRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@link InspectorSource} over the host's real runners: a client script is found
 * on its connection by name, a management script in the management runtime.
 * Either may be stopped; the inspector still edits it, as the old floating
 * windows did, and closes only once the runner is disposed or its client gone.
 */
public final class LiveInspectorSource implements InspectorSource {

    /** The header line under a management script's name. */
    public static final String MANAGEMENT_CONTEXT = "Management script · whole host";

    private final Supplier<List<Connection>> connections;
    private final Supplier<ManagementScriptRuntime> management;
    private final ItemNames itemNames;

    public LiveInspectorSource(CliContext ctx) {
        this(() -> new ArrayList<>(ctx.getConnections()), ctx::getManagementRuntime);
    }

    /** Package-private: the tests' seam, which needs no live connection. */
    LiveInspectorSource(Supplier<List<Connection>> connections, Supplier<ManagementScriptRuntime> management) {
        this.connections = connections;
        this.management = management;
        this.itemNames = new ItemNames(this::anyClient);
    }

    @Override
    public Optional<InspectorTarget> resolve(InspectorSubject subject) {
        return switch (subject) {
            case ClientScript s -> connection(s.clientId())
                    .flatMap(conn -> Optional.ofNullable(conn.getRuntime().findRunner(s.scriptName()))
                            .map(runner -> targetFor(s, conn, runner)));
            case ManagementScript s -> Optional.ofNullable(management.get())
                    .map(runtime -> runtime.findRunner(s.scriptName()))
                    .map(runner -> targetFor(s, runner));
        };
    }

    private InspectorTarget targetFor(ClientScript subject, Connection conn, ScriptRunner runner) {
        String name = runner.getScriptName();
        List<ConfigField> fields = ScriptCode.fields(runner::getConfigFields, name);
        ScriptUI ui = ScriptCode.ui(() -> runner.getScript().getUI(), name);
        return new InspectorTarget(subject, "on " + account(conn) + " · " + conn.getName(),
                ScriptInfo.of(runner.getManifest(), name, fields.size(), ui != null), fields,
                runner::getCurrentConfig, runner::applyConfig, ui, itemNames::of, runner::isDisposed);
    }

    private InspectorTarget targetFor(ManagementScript subject, ManagementScriptRunner runner) {
        String name = runner.getScriptName();
        List<ConfigField> fields = ScriptCode.fields(runner::getConfigFields, name);
        ScriptUI ui = ScriptCode.ui(() -> runner.getScript().getUI(), name);
        return new InspectorTarget(subject, MANAGEMENT_CONTEXT,
                ScriptInfo.of(runner.getManifest(), name, fields.size(), ui != null), fields,
                runner::getCurrentConfig, runner::applyConfig, ui, itemNames::of, runner::isDisposed);
    }

    private Optional<Connection> connection(String name) {
        return connections.get().stream().filter(c -> c.getName().equals(name)).findFirst();
    }

    /**
     * Any connected client's API. Both lookups behind an item name read
     * process-wide indexes, so every client gives the same answer; a management
     * script, which has no client of its own, borrows one.
     */
    private Optional<GameAPI> anyClient() {
        for (Connection conn : connections.get()) {
            GameAPI api = conn.getGameAPI();
            if (api != null) {
                return Optional.of(api);
            }
        }
        return Optional.empty();
    }

    private static String account(Connection conn) {
        String account = conn.getAccountName();
        return account != null && !account.isBlank() ? account : conn.getName();
    }
}
