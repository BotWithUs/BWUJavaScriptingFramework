package com.botwithus.bot.api.gameui;

/**
 * A {@link ListBox} entry as read back, with the client's flags.
 *
 * @param id         the entry's id
 * @param text       what it shows
 * @param isHidden   the client hides it
 * @param isDisabled the client will not select it
 * @param isSelected it is selected
 */
public record ListEntry(int id, String text, boolean isHidden, boolean isDisabled, boolean isSelected) {

    public ListEntry {
        text = text != null ? text : "";
    }

    /** The id and text, as {@link ListBoxProps#entries} takes them. */
    public UiEntry entry() {
        return new UiEntry(id, text);
    }
}
