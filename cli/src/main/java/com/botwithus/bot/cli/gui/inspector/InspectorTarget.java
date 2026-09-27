package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.ui.ScriptUI;
import com.botwithus.bot.cli.gui.usermode.board.ScriptInfo;

import java.util.List;
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
        BooleanSupplier isGone) {

    public InspectorTarget {
        fields = List.copyOf(fields);
    }

    /** The Script UI tab is offered only when the script draws one. */
    public boolean hasCustomUi() {
        return customUi != null;
    }
}
