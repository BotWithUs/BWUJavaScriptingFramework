package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.ui.ScriptUI;

import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * What the config inspector edits: one running script on one client.
 *
 * @param clientId  the card this inspector belongs to
 * @param account   who the script runs on, for the header
 * @param script    header details
 * @param fields    the script's declared settings, possibly empty
 * @param current   the applied config, or {@code null} before the first load
 * @param apply     persists and pushes a new config to the script
 * @param customUi  the script's own UI, or {@code null}
 * @param itemName  resolves an item id to its name, empty when unknown
 * @param isGone    true once the script's runner has been disposed
 */
public record InspectorTarget(
        String clientId,
        String account,
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
}
