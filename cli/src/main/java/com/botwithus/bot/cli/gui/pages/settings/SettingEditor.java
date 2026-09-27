package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.InvalidSettingException;
import com.botwithus.bot.cli.settings.SettingKey;
import com.botwithus.bot.cli.settings.SettingType;

import java.util.Locale;
import java.util.Optional;

/**
 * Applies an edit made on the page to the settings, which save it on their own.
 * A refused edit leaves the setting as it was and comes back as a message for
 * the row, worded in the unit the row shows: a stall threshold typed in seconds
 * is refused in seconds, not in the milliseconds the file stores.
 */
public final class SettingEditor {

    private final HostSettings settings;

    public SettingEditor(HostSettings settings) {
        this.settings = settings;
    }

    /** An edit from a setting's row, in the row's display form. */
    public EditResult editRow(String name, String shownText) {
        Optional<Placement> placement = SettingsLayout.find(name);
        DisplayUnit unit = placement.map(Placement::unit).orElse(DisplayUnit.NONE);
        String stored;
        try {
            stored = unit.stored(shownText);
        } catch (IllegalArgumentException e) {
            return new EditResult.Refused(sentence(e.getMessage()));
        }
        try {
            settings.setText(name, stored);
            return new EditResult.Applied();
        } catch (InvalidSettingException e) {
            return new EditResult.Refused(rowMessage(placement, unit, e));
        }
    }

    /** An edit from the All config keys table, in the form the file stores. */
    public EditResult editRaw(String name, String text) {
        try {
            settings.setText(name, text);
            return new EditResult.Applied();
        } catch (InvalidSettingException e) {
            return new EditResult.Refused(sentence("'" + e.settingName() + "' " + bareRule(e)));
        }
    }

    /** Puts a setting back to its default by removing it from the file. */
    public void reset(String name) {
        settings.find(name).ifPresent(settings::reset);
    }

    private static String rowMessage(Optional<Placement> placement, DisplayUnit unit, InvalidSettingException e) {
        if (placement.isEmpty() || !unit.isScaled()) {
            return ruleOf(e);
        }
        SettingKey<?> key = placement.get().key();
        return switch (key.type()) {
            case SettingType.WholeNumber whole -> "Must be from " + unit.shown(whole.min()) + " to "
                    + unit.shown(whole.max()) + " " + unit.suffix() + ".";
            case SettingType.Flag _, SettingType.Decimal _, SettingType.Text _, SettingType.Choice<?> _ ->
                    ruleOf(e);
        };
    }

    /** The exception's rule without the key name it starts with, as a sentence. */
    private static String ruleOf(InvalidSettingException e) {
        return sentence(bareRule(e));
    }

    /** The exception's message without the key name it starts with: {@code "must be ..."}. */
    private static String bareRule(InvalidSettingException e) {
        String message = e.getMessage();
        String prefix = e.settingName() + " ";
        return message.startsWith(prefix) ? message.substring(prefix.length()) : message;
    }

    private static String sentence(String text) {
        if (text.isEmpty()) {
            return text;
        }
        String first = text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
        return first.endsWith(".") ? first : first + ".";
    }
}
