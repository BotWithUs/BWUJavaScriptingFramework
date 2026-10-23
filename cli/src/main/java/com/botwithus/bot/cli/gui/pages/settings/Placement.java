package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.settings.SettingKey;

import java.util.Objects;

/**
 * Where one setting appears on the Settings page, and how.
 *
 * @param section     the section whose rows it is in
 * @param key         the setting
 * @param unit        how a number is shown; {@link DisplayUnit#NONE} for anything else
 * @param description the row's one line; the key's own when it reads right in the row's unit
 */
public record Placement(SettingsSection section, SettingKey<?> key, DisplayUnit unit, String description) {

    public Placement {
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(description, "description");
    }

    /** A row that uses the key's own description. */
    public static Placement of(SettingsSection section, SettingKey<?> key, DisplayUnit unit) {
        return new Placement(section, key, unit, key.description());
    }

    /** A switch, text field or choice: no unit, the key's own description. */
    public static Placement of(SettingsSection section, SettingKey<?> key) {
        return of(section, key, DisplayUnit.NONE);
    }
}
