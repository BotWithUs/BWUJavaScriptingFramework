package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.ui.ScriptUI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Supplier;

/**
 * Calls into script code on the render thread. A script's {@code getConfigFields()}
 * or {@code getUI()} that throws reads as "none" instead of taking the frame down.
 */
public final class ScriptCode {

    private static final Logger log = LoggerFactory.getLogger(ScriptCode.class);

    private ScriptCode() {}

    /** The script's fields, or an empty list when it declares none or the call throws. */
    public static List<ConfigField> fields(Supplier<List<ConfigField>> call, String scriptName) {
        try {
            List<ConfigField> fields = call.get();
            return fields != null ? fields : List.of();
        } catch (RuntimeException e) {
            log.debug("getConfigFields() threw for {}: {}", scriptName, e.toString());
            return List.of();
        }
    }

    /** The script's own UI, or {@code null} when it has none or the call throws. */
    public static ScriptUI ui(Supplier<ScriptUI> call, String scriptName) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            log.debug("getUI() threw for {}: {}", scriptName, e.toString());
            return null;
        }
    }
}
