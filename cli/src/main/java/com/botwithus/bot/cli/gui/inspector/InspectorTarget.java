package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.ui.ScriptUI;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ClientScript;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ManagementScript;
import com.botwithus.bot.cli.gui.scriptui.ScriptUiSlot;
import com.botwithus.bot.cli.gui.usermode.board.ScriptInfo;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * What the config inspector edits this frame: one script's settings and UI,
 * resolved from its {@link InspectorSubject}.
 *
 * @param subject   which script this is, and so the inspector's kind
 * @param context   the header's second line, e.g. "on Oakheart · BotWithUs_14208"
 * @param script    header details
 * @param fields    the script's declared settings, possibly empty
 * @param current   the applied config, or {@code null} before the first load
 * @param apply     persists and pushes a new config to the script
 * @param customUi  the script's own UI, or {@code null}
 * @param itemName  resolves an item id to its name, empty when unknown
 * @param isGone    true once the script's runner has been disposed
 * @param settingsFor a management script's target picker, and what each field
 *                  inherits for the picked target; {@link SettingsFor#none()}
 *                  for a client script
 * @param startedAt when the script's current run started, or
 *                  {@link ScriptUiSlot#NEVER_STARTED}; a change is a restart,
 *                  which gives a stopped script UI its reloads back
 */
public record InspectorTarget(
        InspectorSubject subject,
        String context,
        ScriptInfo script,
        List<ConfigField> fields,
        Supplier<ScriptConfig> current,
        Consumer<ScriptConfig> apply,
        ScriptUI customUi,
        IntFunction<Optional<String>> itemName,
        BooleanSupplier isGone,
        SettingsFor settingsFor,
        Instant startedAt) {

    public InspectorTarget {
        fields = List.copyOf(fields);
        Objects.requireNonNull(settingsFor, "settingsFor");
        startedAt = startedAt == null ? ScriptUiSlot.NEVER_STARTED : startedAt;
    }

    /** A target whose run start is not known: its script UI's failures last until the host restarts. */
    public InspectorTarget(InspectorSubject subject, String context, ScriptInfo script, List<ConfigField> fields,
                           Supplier<ScriptConfig> current, Consumer<ScriptConfig> apply, ScriptUI customUi,
                           IntFunction<Optional<String>> itemName, BooleanSupplier isGone,
                           SettingsFor settingsFor) {
        this(subject, context, script, fields, current, apply, customUi, itemName, isGone, settingsFor,
                ScriptUiSlot.NEVER_STARTED);
    }

    /** A target with no "Settings for" picker. */
    public InspectorTarget(InspectorSubject subject, String context, ScriptInfo script, List<ConfigField> fields,
                           Supplier<ScriptConfig> current, Consumer<ScriptConfig> apply, ScriptUI customUi,
                           IntFunction<Optional<String>> itemName, BooleanSupplier isGone) {
        this(subject, context, script, fields, current, apply, customUi, itemName, isGone, SettingsFor.none());
    }

    /** The Script UI tab is offered only when the script draws one. */
    public boolean hasCustomUi() {
        return customUi != null;
    }

    /**
     * The script's UI as the sandboxed drawer draws it. Keyed by client and script
     * rather than by subject, so picking a management script's target does not
     * count as a different UI. Only call when {@link #hasCustomUi()}.
     */
    public ScriptUiSlot scriptUiSlot() {
        String owner = switch (subject) {
            case ClientScript s -> "client/" + s.clientId() + "/" + s.scriptName();
            case ManagementScript s -> "management/" + s.scriptName();
        };
        return new ScriptUiSlot(owner, script.name(), customUi, startedAt);
    }
}
