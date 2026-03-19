package com.botwithus.bot.cli.gui;

import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.ui.ScriptUI;
import com.botwithus.bot.core.runtime.ManagementScriptRunner;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Floating ImGui window for editing a management script's configuration
 * or rendering its custom UI.
 */
public class ManagementConfigPanel {

    private ManagementScriptRunner runner;
    private List<ConfigField> fields;
    private final Map<String, Object> editValues = new LinkedHashMap<>();
    private final ImBoolean open = new ImBoolean(false);

    public void open(ManagementScriptRunner runner) {
        this.runner = runner;

        // Check for custom UI first
        ScriptUI ui = runner.getScript().getUI();
        if (ui != null) {
            this.fields = null;
            open.set(true);
            return;
        }

        // Fall back to ConfigField-based form
        this.fields = runner.getConfigFields();
        if (fields == null || fields.isEmpty()) return;

        ScriptConfig current = runner.getCurrentConfig();
        editValues.clear();
        for (ConfigField field : fields) {
            switch (field.kind()) {
                case INT, ITEM_ID -> {
                    int val = current != null
                            ? current.getInt(field.key(), ((Number) field.defaultValue()).intValue())
                            : ((Number) field.defaultValue()).intValue();
                    editValues.put(field.key(), new ImInt(val));
                }
                case STRING -> {
                    String val = current != null
                            ? current.getString(field.key(), (String) field.defaultValue())
                            : (String) field.defaultValue();
                    editValues.put(field.key(), new ImString(val != null ? val : "", 256));
                }
                case BOOLEAN -> {
                    boolean val = current != null
                            ? current.getBoolean(field.key(), (Boolean) field.defaultValue())
                            : (Boolean) field.defaultValue();
                    editValues.put(field.key(), new ImBoolean(val));
                }
                case CHOICE -> {
                    String val = current != null
                            ? current.getString(field.key(), (String) field.defaultValue())
                            : (String) field.defaultValue();
                    int idx = field.choices().indexOf(val);
                    editValues.put(field.key(), new ImInt(Math.max(idx, 0)));
                }
            }
        }
        open.set(true);
    }

    public boolean isOpen() {
        return open.get();
    }

    public void render() {
        if (!open.get() || runner == null) return;
        if (runner.isDisposed()) {
            open.set(false);
            runner = null;
            return;
        }

        // Custom UI mode
        ScriptUI ui = runner.getScript().getUI();
        if (ui != null) {
            ImGui.setNextWindowSize(925, 690, ImGuiCond.FirstUseEver);
            if (ImGui.begin(runner.getScriptName() + " Config###mgmtUIWindow", open,
                    ImGuiWindowFlags.NoCollapse)) {
                try {
                    ui.render();
                } catch (Exception e) {
                    ImGui.textColored(ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 1f,
                            "UI error: " + e.getMessage());
                }
            }
            ImGui.end();
            return;
        }

        // ConfigField form mode
        if (fields == null || fields.isEmpty()) {
            open.set(false);
            return;
        }

        ImGui.setNextWindowSize(350, 0);
        if (ImGui.begin("Config: " + runner.getScriptName() + "###mgmtConfigWindow", open,
                ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoCollapse)) {

            for (ConfigField field : fields) {
                Object editVal = editValues.get(field.key());
                if (editVal == null) continue;

                switch (field.kind()) {
                    case INT -> ImGui.inputInt(field.label(), (ImInt) editVal);
                    case ITEM_ID -> ImGui.inputInt(field.label() + " (Item ID)", (ImInt) editVal);
                    case STRING -> ImGui.inputText(field.label(), (ImString) editVal);
                    case BOOLEAN -> ImGui.checkbox(field.label(), (ImBoolean) editVal);
                    case CHOICE -> {
                        String[] items = field.choices().toArray(new String[0]);
                        ImGui.combo(field.label(), (ImInt) editVal, items);
                    }
                }
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
            Object editVal = editValues.get(field.key());
            if (editVal == null) continue;

            switch (field.kind()) {
                case INT, ITEM_ID -> values.put(field.key(), String.valueOf(((ImInt) editVal).get()));
                case STRING -> values.put(field.key(), ((ImString) editVal).get());
                case BOOLEAN -> values.put(field.key(), String.valueOf(((ImBoolean) editVal).get()));
                case CHOICE -> {
                    int idx = ((ImInt) editVal).get();
                    if (idx >= 0 && idx < field.choices().size()) {
                        values.put(field.key(), field.choices().get(idx));
                    }
                }
            }
        }
        runner.applyConfig(new ScriptConfig(values));
    }

    private void resetToDefaults() {
        for (ConfigField field : fields) {
            Object editVal = editValues.get(field.key());
            if (editVal == null) continue;

            switch (field.kind()) {
                case INT, ITEM_ID -> ((ImInt) editVal).set(((Number) field.defaultValue()).intValue());
                case STRING -> ((ImString) editVal).set((String) field.defaultValue());
                case BOOLEAN -> ((ImBoolean) editVal).set((Boolean) field.defaultValue());
                case CHOICE -> {
                    int idx = field.choices().indexOf(field.defaultValue());
                    ((ImInt) editVal).set(Math.max(idx, 0));
                }
            }
        }
    }
}
