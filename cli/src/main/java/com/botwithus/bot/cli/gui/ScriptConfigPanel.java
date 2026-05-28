package com.botwithus.bot.cli.gui;

import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ConfigField.BoolField;
import com.botwithus.bot.api.config.ConfigField.ChoiceField;
import com.botwithus.bot.api.config.ConfigField.IntField;
import com.botwithus.bot.api.config.ConfigField.ItemIdField;
import com.botwithus.bot.api.config.ConfigField.StringField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.core.runtime.ScriptRunner;

import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ImGui floating window for editing a script's configuration fields.
 */
public class ScriptConfigPanel {

    private static final int STRING_BUFFER_SIZE = 256;

    private ScriptRunner runner;
    private List<ConfigField> fields;
    private final EditState edit = new EditState();
    private final ImBoolean open = new ImBoolean(false);

    public void open(ScriptRunner runner) {
        this.runner = runner;
        this.fields = runner.getConfigFields();
        if (fields == null || fields.isEmpty()) return;

        ScriptConfig current = runner.getCurrentConfig();
        edit.clear();
        for (ConfigField field : fields) {
            edit.seed(field, current);
        }
        open.set(true);
    }

    public boolean isOpen() {
        return open.get();
    }

    public void close() {
        open.set(false);
    }

    /** Call from the main ImGui render loop. */
    public void render() {
        if (!open.get() || runner == null || fields == null || fields.isEmpty()) return;
        if (runner.isDisposed()) {
            open.set(false);
            runner = null;
            return;
        }

        ImGui.setNextWindowSize(350, 0);
        if (ImGui.begin("Config: " + runner.getScriptName(), open,
                ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoCollapse)) {

            for (ConfigField field : fields) {
                edit.renderWidget(field);
            }

            ImGui.separator();

            if (ImGui.button("Apply")) {
                applyConfig();
            }
            ImGui.sameLine();
            if (ImGui.button("Reset")) {
                resetToDefaults();
            }
            ImGui.sameLine();
            if (ImGui.button("Close")) {
                open.set(false);
            }
        }
        ImGui.end();
    }

    private void applyConfig() {
        Map<String, String> values = new LinkedHashMap<>();
        for (ConfigField field : fields) {
            edit.collect(field, values);
        }
        runner.applyConfig(new ScriptConfig(values));
    }

    private void resetToDefaults() {
        for (ConfigField field : fields) {
            edit.reset(field);
        }
    }

    /**
     * Typed editor state for the open form. Each field variant stores its mutable
     * ImGui wrapper in the matching typed map, so dispatch never needs a cast.
     */
    private static final class EditState {

        private final Map<String, ImInt> ints = new LinkedHashMap<>();
        private final Map<String, ImString> strings = new LinkedHashMap<>();
        private final Map<String, ImBoolean> bools = new LinkedHashMap<>();

        void clear() {
            ints.clear();
            strings.clear();
            bools.clear();
        }

        void seed(ConfigField field, ScriptConfig current) {
            switch (field) {
                case IntField f -> ints.put(f.key(), new ImInt(intOr(current, f.key(), f.value())));
                case ItemIdField f -> ints.put(f.key(), new ImInt(intOr(current, f.key(), f.value())));
                case BoolField f -> bools.put(f.key(), new ImBoolean(boolOr(current, f.key(), f.value())));
                case StringField f -> {
                    String val = stringOr(current, f.key(), f.value());
                    strings.put(f.key(), new ImString(val != null ? val : "", STRING_BUFFER_SIZE));
                }
                case ChoiceField f -> {
                    String val = stringOr(current, f.key(), f.value());
                    ints.put(f.key(), new ImInt(Math.max(f.choices().indexOf(val), 0)));
                }
            }
        }

        void renderWidget(ConfigField field) {
            switch (field) {
                case IntField f -> ImGui.inputInt(f.label(), ints.get(f.key()));
                case ItemIdField f -> ImGui.inputInt(f.label() + " (Item ID)", ints.get(f.key()));
                case StringField f -> ImGui.inputText(f.label(), strings.get(f.key()));
                case BoolField f -> ImGui.checkbox(f.label(), bools.get(f.key()));
                case ChoiceField f -> ImGui.combo(f.label(), ints.get(f.key()), f.choices().toArray(new String[0]));
            }
        }

        void collect(ConfigField field, Map<String, String> values) {
            switch (field) {
                case IntField f -> values.put(f.key(), String.valueOf(ints.get(f.key()).get()));
                case ItemIdField f -> values.put(f.key(), String.valueOf(ints.get(f.key()).get()));
                case StringField f -> values.put(f.key(), strings.get(f.key()).get());
                case BoolField f -> values.put(f.key(), String.valueOf(bools.get(f.key()).get()));
                case ChoiceField f -> {
                    int idx = ints.get(f.key()).get();
                    if (idx >= 0 && idx < f.choices().size()) {
                        values.put(f.key(), f.choices().get(idx));
                    }
                }
            }
        }

        void reset(ConfigField field) {
            switch (field) {
                case IntField f -> ints.get(f.key()).set(f.value());
                case ItemIdField f -> ints.get(f.key()).set(f.value());
                case StringField f -> strings.get(f.key()).set(f.value());
                case BoolField f -> bools.get(f.key()).set(f.value());
                case ChoiceField f -> ints.get(f.key()).set(Math.max(f.choices().indexOf(f.value()), 0));
            }
        }

        private static int intOr(ScriptConfig current, String key, int defaultValue) {
            return current != null ? current.getInt(key, defaultValue) : defaultValue;
        }

        private static boolean boolOr(ScriptConfig current, String key, boolean defaultValue) {
            return current != null ? current.getBoolean(key, defaultValue) : defaultValue;
        }

        private static String stringOr(ScriptConfig current, String key, String defaultValue) {
            return current != null ? current.getString(key, defaultValue) : defaultValue;
        }
    }
}
