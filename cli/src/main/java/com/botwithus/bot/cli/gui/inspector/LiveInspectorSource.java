package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.ui.ScriptUI;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ClientScript;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ManagementScript;
import com.botwithus.bot.cli.gui.inspector.SettingsFor.Choice;
import com.botwithus.bot.cli.gui.inspector.SettingsFor.InheritedValue;
import com.botwithus.bot.cli.gui.usermode.board.ScriptInfo;
import com.botwithus.bot.cli.management.ManagementSettings;
import com.botwithus.bot.cli.management.ManagementSettings.TargetView;
import com.botwithus.bot.cli.management.ManagementTargets;
import com.botwithus.bot.cli.management.Target;
import com.botwithus.bot.cli.management.TargetLabels;
import com.botwithus.bot.core.runtime.ManagementScriptRunner;
import com.botwithus.bot.core.runtime.ManagementScriptRuntime;
import com.botwithus.bot.core.runtime.ScriptRunner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@link InspectorSource} over the host's real runners: a client script is found
 * on its connection by name, a management script in the management runtime.
 * Either may be stopped; the inspector still edits it, as the old floating
 * windows did, and closes only once the runner is disposed or its client gone.
 */
public final class LiveInspectorSource implements InspectorSource {

    /** How the header line under a management script's name starts. */
    private static final String MANAGEMENT_CONTEXT = "Management script";
    private static final String NOT_APPLIED = "not applied";
    private static final String SEP = " · ";
    /** The picker's note on the defaults. */
    static final String DEFAULTS_NOTE = "Every target uses these unless it has its own value.";

    /**
     * What the inspector reads and changes a management script's per-target settings through.
     *
     * @param targets  each script's targets
     * @param settings their settings and the order they are inherited in
     * @param labels   what the user calls each target and each source
     */
    public record ManagementAccess(ManagementTargets targets, ManagementSettings settings, TargetLabels labels) {
        public ManagementAccess {
            Objects.requireNonNull(targets, "targets");
            Objects.requireNonNull(settings, "settings");
            Objects.requireNonNull(labels, "labels");
        }
    }

    private final Supplier<List<Connection>> connections;
    private final Supplier<ManagementScriptRuntime> management;
    private final ManagementAccess access;
    private final ItemNames itemNames;

    public LiveInspectorSource(CliContext ctx) {
        this(() -> new ArrayList<>(ctx.getConnections()), ctx::getManagementRuntime,
                new ManagementAccess(ctx.getManagementTargets(), ctx.getManagementSettings(),
                        new TargetLabels(ctx.getGroupStore(), ctx::accountNameOf)));
    }

    /**
     * The header line under a management script's name, from what it manages:
     * "Management script · Woodcutters", "Management script · 3 targets", or
     * "Management script · not applied" when it manages nothing.
     *
     * @param targetLabels the labels of the script's targets, in order
     */
    public static String managementContext(List<String> targetLabels) {
        String what = switch (targetLabels.size()) {
            case 0 -> NOT_APPLIED;
            case 1 -> targetLabels.getFirst();
            default -> targetLabels.size() + " targets";
        };
        return MANAGEMENT_CONTEXT + SEP + what;
    }

    /** Package-private: the tests' seam, which needs no live connection. */
    LiveInspectorSource(Supplier<List<Connection>> connections, Supplier<ManagementScriptRuntime> management,
                        ManagementAccess access) {
        this.connections = connections;
        this.management = management;
        this.access = Objects.requireNonNull(access, "access");
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

    /**
     * A management script's form: on its defaults, applied through the runner so
     * the script hears of them, or on one target's own settings. A target that
     * is no longer one of the script's, or cannot be picked, shows the defaults.
     */
    private InspectorTarget targetFor(ManagementScript subject, ManagementScriptRunner runner) {
        String name = runner.getScriptName();
        List<ConfigField> fields = ScriptCode.fields(runner::getConfigFields, name);
        ScriptUI ui = ScriptCode.ui(() -> runner.getScript().getUI(), name);
        ScriptInfo info = ScriptInfo.of(runner.getManifest(), name, fields.size(), ui != null);
        List<Target> pickable = pickableTargets(name);
        Optional<Target> picked = subject.settingsFor().filter(pickable::contains);
        String context = managementContext(access.targets().targetsOf(name).stream()
                .map(access.labels()::of).toList());
        if (picked.isEmpty()) {
            ScriptConfig defaults = access.settings().defaults(name, fields);
            return new InspectorTarget(subject.withSettingsFor(picked), context, info, fields,
                    () -> defaults, runner::applyConfig, ui, itemNames::of, runner::isDisposed,
                    picker(name, pickable, Optional.empty(), DEFAULTS_NOTE, Map.of()));
        }
        Target target = picked.get();
        TargetView view = access.settings().view(name, target, fields);
        return new InspectorTarget(subject, context, info, fields, view::merged,
                cfg -> access.settings().apply(name, target, cfg, fields), ui, itemNames::of, runner::isDisposed,
                picker(name, pickable, picked, targetNote(name, target), inheritedLabels(view)));
    }

    /**
     * The targets the picker offers: none for a script with no targets, or one
     * that manages the whole host, which uses only the defaults.
     */
    private List<Target> pickableTargets(String script) {
        List<Target> targets = access.targets().targetsOf(script);
        boolean isWholeHost = targets.stream().anyMatch(target -> switch (target) {
            case Target.Host _ -> true;
            case Target.Group _, Target.ClientScript _ -> false;
        });
        return isWholeHost ? List.of() : targets;
    }

    private SettingsFor picker(String script, List<Target> pickable, Optional<Target> picked, String note,
                               Map<String, InheritedValue> inherited) {
        if (pickable.isEmpty()) {
            return SettingsFor.none();
        }
        List<Choice> choices = pickable.stream()
                .map(target -> new Choice(target, access.labels().of(target),
                        access.settings().ownCount(script, target)))
                .toList();
        return new SettingsFor(choices, picked, note, inherited);
    }

    private String targetNote(String script, Target target) {
        String follows = access.settings().inheritsFromAGroup(script, target)
                ? "its group, then the defaults" : "the defaults";
        return "Change a value to give " + access.labels().of(target) + " its own. Anything left alone follows "
                + follows + ".";
    }

    private Map<String, InheritedValue> inheritedLabels(TargetView view) {
        Map<String, InheritedValue> labelled = new LinkedHashMap<>();
        view.inherited().forEach((key, inherited) -> labelled.put(key,
                new InheritedValue(inherited.value(), access.labels().of(inherited.source()))));
        return labelled;
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
