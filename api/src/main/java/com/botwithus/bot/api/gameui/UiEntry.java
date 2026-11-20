package com.botwithus.bot.api.gameui;

/**
 * One entry of a {@link ComboField} or a {@link ListBox}.
 *
 * @param id   the script's id for it; unique within the component, and what events
 *             and selections name
 * @param text what it shows; at most {@link UiLimits#MAX_ENTRY_TEXT_BYTES} bytes of UTF-8
 */
public record UiEntry(int id, String text) {

    public UiEntry {
        if (text == null) {
            throw new IllegalArgumentException("text");
        }
    }
}
