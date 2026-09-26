package com.botwithus.bot.cli.gui.usermode;

import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ConfigField.BoolField;
import com.botwithus.bot.api.config.ConfigField.ChoiceField;
import com.botwithus.bot.api.config.ConfigField.IntField;
import com.botwithus.bot.api.config.ConfigField.ItemIdField;
import com.botwithus.bot.api.config.ConfigField.StringField;
import com.botwithus.bot.api.config.ScriptConfig;

import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The inspector's pending edits for one script, against the config it last saw
 * applied. Each field variant keeps its ImGui wrapper in a typed map, so nothing
 * is cast.
 *
 * <p>When the applied config changes underneath (the script's own UI saved
 * something), fields the user has not touched follow it; fields they have edited
 * keep their edit. Without that, Apply would push back the values from when the
 * inspector opened and silently undo the script UI's change.</p>
 */
final class ConfigEdits {

    private static final int STRING_CAPACITY = 256;

    private final List<ConfigField> fields;
    private final Map<String, ImInt> ints = new LinkedHashMap<>();
    private final Map<String, ImString> strings = new LinkedHashMap<>();
    private final Map<String, ImBoolean> bools = new LinkedHashMap<>();
    private Map<String, String> applied;

    ConfigEdits(List<ConfigField> fields, ScriptConfig current) {
        this.fields = List.copyOf(fields);
        this.applied = appliedValues(current);
        for (ConfigField field : this.fields) {
            seed(field, applied.get(field.key()));
        }
    }

    List<ConfigField> fields() {
        return fields;
    }

    ImInt intOf(String key) {
        return ints.get(key);
    }

    ImString stringOf(String key) {
        return strings.get(key);
    }

    ImBoolean boolOf(String key) {
        return bools.get(key);
    }

    /** Follows a new applied config for every field the user has not edited. */
    void sync(ScriptConfig current) {
        Map<String, String> now = appliedValues(current);
        if (now.equals(applied)) {
            return;
        }
        for (ConfigField field : fields) {
            if (!isDirty(field)) {
                seed(field, now.get(field.key()));
            }
        }
        applied = now;
    }

    boolean isDirty(ConfigField field) {
        return !Objects.equals(pending(field), applied.get(field.key()));
    }

    int dirtyCount() {
        return (int) fields.stream().filter(this::isDirty).count();
    }

    /** Throws away every edit. */
    void revert() {
        for (ConfigField field : fields) {
            seed(field, applied.get(field.key()));
        }
    }

    /** The full config to apply: every field's pending value. */
    ScriptConfig toConfig() {
        Map<String, String> values = new LinkedHashMap<>();
        for (ConfigField field : fields) {
            values.put(field.key(), pending(field));
        }
        return new ScriptConfig(values);
    }

    /** Marks the pending values as applied, so the form reads "Up to date" at once. */
    void markApplied() {
        Map<String, String> values = new LinkedHashMap<>();
        for (ConfigField field : fields) {
            values.put(field.key(), pending(field));
        }
        applied = values;
    }

    String pending(ConfigField field) {
        return switch (field) {
            case IntField f -> String.valueOf(ints.get(f.key()).get());
            case ItemIdField f -> String.valueOf(ints.get(f.key()).get());
            case StringField f -> strings.get(f.key()).get();
            case BoolField f -> String.valueOf(bools.get(f.key()).get());
            case ChoiceField f -> {
                int idx = ints.get(f.key()).get();
                yield idx >= 0 && idx < f.choices().size() ? f.choices().get(idx) : f.value();
            }
        };
    }

    private Map<String, String> appliedValues(ScriptConfig current) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> source = current != null ? current.asMap() : Map.of();
        for (ConfigField field : fields) {
            values.put(field.key(), source.getOrDefault(field.key(), field.defaultAsString()));
        }
        return values;
    }

    private void seed(ConfigField field, String value) {
        switch (field) {
            case IntField f -> ints.computeIfAbsent(f.key(), k -> new ImInt()).set(parseInt(value, f.value()));
            case ItemIdField f -> ints.computeIfAbsent(f.key(), k -> new ImInt()).set(parseInt(value, f.value()));
            case BoolField f -> bools.computeIfAbsent(f.key(), k -> new ImBoolean()).set(Boolean.parseBoolean(value));
            case StringField f -> strings.computeIfAbsent(f.key(), k -> new ImString(STRING_CAPACITY))
                    .set(value != null ? value : "");
            case ChoiceField f -> ints.computeIfAbsent(f.key(), k -> new ImInt())
                    .set(Math.max(0, f.choices().indexOf(value)));
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value != null ? Integer.parseInt(value.strip()) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
