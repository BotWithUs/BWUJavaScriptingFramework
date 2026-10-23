package com.botwithus.bot.cli.gui.pages.settings;

import java.util.Locale;

/**
 * One line of the All config keys table.
 *
 * @param name       the property name
 * @param value      its effective value, as stored
 * @param shownAs    the label of the row above that edits it, or a dash when none does
 * @param isEditable {@code false} for a name no known setting claims; it is kept but not edited here
 * @param isExplicit the file sets it, rather than it being the default
 */
public record RawKeyRow(String name, String value, String shownAs, boolean isEditable, boolean isExplicit) {

    String findText() {
        return (name + " " + value + " " + shownAs).toLowerCase(Locale.ROOT);
    }
}
