package com.botwithus.bot.cli.gui.preview;

import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.gui.inspector.SettingsFor;
import com.botwithus.bot.cli.gui.inspector.SettingsFor.Choice;
import com.botwithus.bot.cli.gui.inspector.SettingsFor.InheritedValue;
import com.botwithus.bot.cli.management.Target;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DEV ONLY. Break Scheduler's targets and per-target values for the preview,
 * as the design's example has them: Woodcutters (Oakheart, Fernmoss), Fernmoss's
 * Divination and Kestrel Moor's Walk to Flag. Woodcutters sets two values of its
 * own, Fernmoss's Divination one, Kestrel Moor none. The merge mirrors the
 * host's order (client script, then its group, then the defaults) by hand; the
 * real order is tested against the real classes, not here.
 */
final class FixtureManagementSettings {

    /** The fleet's Fernmoss and Kestrel Moor accounts; see {@link FixtureFleet}. */
    private static final String FERNMOSS_UUID = "b71d09e4-2c61-4f3e-8a57-1d9e0c4b6f33";
    private static final String KESTREL_MOOR_UUID = "81c5e0b2-6d4f-4a37-b9e8-3f0a2c7d5e16";

    static final Target WOODCUTTERS = new Target.Group(GroupId.migratedFrom("Woodcutters"));
    static final Target FERNMOSS_DIVINATION = new Target.ClientScript(FERNMOSS_UUID, "Divination");
    static final Target KESTREL_WALK = new Target.ClientScript(KESTREL_MOOR_UUID, "Walk to Flag");

    private static final String WOODCUTTERS_NAME = "Woodcutters";
    private static final String DEFAULTS = "defaults";
    private static final Map<Target, String> LABELS = Map.of(
            WOODCUTTERS, WOODCUTTERS_NAME,
            FERNMOSS_DIVINATION, "Fernmoss · Divination",
            KESTREL_WALK, "Kestrel Moor · Walk to Flag");
    private static final List<Target> TARGETS = List.of(WOODCUTTERS, FERNMOSS_DIVINATION, KESTREL_WALK);
    private static final Map<Target, Map<String, String>> OWN = Map.of(
            WOODCUTTERS, Map.of("breakEvery", "120", "logOut", "false"),
            FERNMOSS_DIVINATION, Map.of("breakLength", "20"),
            KESTREL_WALK, Map.of());

    private final List<ConfigField> fields;

    FixtureManagementSettings(List<ConfigField> fields) {
        this.fields = List.copyOf(fields);
    }

    /** {@code target} when it is one of the script's, else the defaults. */
    Optional<Target> picked(Optional<Target> target) {
        return target.filter(TARGETS::contains);
    }

    /** The form's applied config: the target's merged values, or the declared defaults. */
    ScriptConfig current(Optional<Target> picked) {
        Map<String, String> values = new LinkedHashMap<>();
        for (ConfigField field : fields) {
            values.put(field.key(), picked.map(target -> merged(target, field)).orElse(field.defaultAsString()));
        }
        return new ScriptConfig(values);
    }

    /** The picker, on {@code picked}. */
    SettingsFor picker(Optional<Target> picked) {
        List<Choice> choices = TARGETS.stream()
                .map(target -> new Choice(target, LABELS.get(target), OWN.get(target).size()))
                .toList();
        Map<String, InheritedValue> inherited = new LinkedHashMap<>();
        picked.ifPresent(target -> fields.forEach(field -> inherited.put(field.key(), inherited(target, field))));
        return new SettingsFor(choices, picked, picked.map(this::note)
                .orElse("Every target uses these unless it has its own value."), inherited);
    }

    private String merged(Target target, ConfigField field) {
        String own = OWN.get(target).get(field.key());
        return own != null ? own : inherited(target, field).value();
    }

    private InheritedValue inherited(Target target, ConfigField field) {
        String fromGroup = target.equals(FERNMOSS_DIVINATION) ? OWN.get(WOODCUTTERS).get(field.key()) : null;
        return fromGroup != null ? new InheritedValue(fromGroup, WOODCUTTERS_NAME)
                : new InheritedValue(field.defaultAsString(), DEFAULTS);
    }

    private String note(Target target) {
        String follows = target.equals(FERNMOSS_DIVINATION) ? "its group, then the defaults" : "the defaults";
        return "Change a value to give " + LABELS.get(target) + " its own. Anything left alone follows "
                + follows + ".";
    }
}
