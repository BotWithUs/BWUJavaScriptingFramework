package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.cli.management.Target;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The "Settings for" picker over a management script's form: the targets it
 * can edit instead of the defaults, the one it edits now, and, for that target,
 * what each field inherits and from where.
 *
 * @param choices   the script's targets, in the order the script lists them;
 *                  empty when there is nothing to pick, and then no picker shows
 * @param selected  the target the form edits; empty for the defaults
 * @param note      one line under the picker saying what the form changes
 * @param inherited for the selected target, each field's inherited value by key
 */
public record SettingsFor(List<Choice> choices, Optional<Target> selected, String note,
                          Map<String, InheritedValue> inherited) {

    /** The picker's first entry, which edits the defaults. */
    public static final String DEFAULTS_CHOICE = "Defaults · every target";

    /**
     * One target in the picker.
     *
     * @param target   the target
     * @param label    its name, e.g. "Oakheart · Woodcutting"
     * @param ownCount how many values it sets itself
     */
    public record Choice(Target target, String label, int ownCount) {
        public Choice {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(label, "label");
        }

        /** The picker's text: the label, and "· 2 own" when it has values of its own. */
        public String text() {
            return ownCount > 0 ? label + " · " + ownCount + " own" : label;
        }
    }

    /**
     * What a field gets when the selected target has no value of its own.
     *
     * @param value the value
     * @param from  where it comes from, as in "from defaults" or "from Woodcutters"
     */
    public record InheritedValue(String value, String from) {
        public InheritedValue {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(from, "from");
        }
    }

    public SettingsFor {
        choices = List.copyOf(choices);
        Objects.requireNonNull(selected, "selected");
        Objects.requireNonNull(note, "note");
        inherited = Map.copyOf(inherited);
    }

    /** No picker: a client script, or a management script with nothing to pick. */
    public static SettingsFor none() {
        return new SettingsFor(List.of(), Optional.empty(), "", Map.of());
    }

    /** Whether the picker is drawn. */
    public boolean isShown() {
        return !choices.isEmpty();
    }

    /** The selected target's inherited value for {@code key}; empty on the defaults. */
    public Optional<InheritedValue> inheritedOf(String key) {
        return selected.isPresent() ? Optional.ofNullable(inherited.get(key)) : Optional.empty();
    }

    /** The picker's entries: the defaults, then each target. */
    public List<String> options() {
        return Stream.concat(Stream.of(DEFAULTS_CHOICE),
                choices.stream().map(Choice::text)).toList();
    }

    /** The picker's selected entry: 0 for the defaults, else the target's place plus one. */
    public int selectedIndex() {
        return selected.map(target -> {
            for (int i = 0; i < choices.size(); i++) {
                if (choices.get(i).target().equals(target)) {
                    return i + 1;
                }
            }
            return 0;
        }).orElse(0);
    }

    /** The target at picker entry {@code index}; empty for the defaults or an entry that is not there. */
    public Optional<Target> targetAt(int index) {
        return index >= 1 && index <= choices.size() ? Optional.of(choices.get(index - 1).target()) : Optional.empty();
    }
}
