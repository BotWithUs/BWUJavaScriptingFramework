package com.botwithus.bot.cli.gui.pages.settings;

import java.util.List;
import java.util.Locale;

/**
 * One saved account profile: who it is and what resumes when it comes back.
 *
 * @param uuid        the account UUID the profile is keyed by
 * @param name        the display name last seen, or empty when none was
 * @param scripts     the scripts that start again, in order
 * @param isAutoStart whether they start again at all
 */
public record AccountRow(String uuid, String name, List<String> scripts, boolean isAutoStart) {

    private static final int SHORT_UUID_LENGTH = 8;

    public AccountRow {
        scripts = List.copyOf(scripts);
    }

    /** The name, or the short UUID when no name was ever seen. */
    public String title() {
        return name.isBlank() ? shortUuid() : name;
    }

    /** The first characters of the UUID, enough to tell accounts apart. */
    public String shortUuid() {
        return uuid.length() <= SHORT_UUID_LENGTH ? uuid : uuid.substring(0, SHORT_UUID_LENGTH);
    }

    String findText() {
        return (name + " " + uuid + " " + String.join(" ", scripts)).toLowerCase(Locale.ROOT);
    }
}
