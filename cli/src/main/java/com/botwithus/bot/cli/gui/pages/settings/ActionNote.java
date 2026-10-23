package com.botwithus.bot.cli.gui.pages.settings;

/**
 * One line reporting what the last button on the page did, such as
 * "Metrics reset on 3 clients." It replaces the previous one.
 *
 * @param isError draws it in the danger colour
 */
public record ActionNote(String text, boolean isError) {

    public static ActionNote done(String text) {
        return new ActionNote(text, false);
    }

    public static ActionNote failed(String text) {
        return new ActionNote(text, true);
    }
}
