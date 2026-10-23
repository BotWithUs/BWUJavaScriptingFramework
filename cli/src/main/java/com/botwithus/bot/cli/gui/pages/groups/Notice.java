package com.botwithus.bot.cli.gui.pages.groups;

import java.util.Objects;

/**
 * What the last thing asked of the Groups page did, in words for the user. The
 * page shows it until it is dismissed or the next one replaces it.
 *
 * @param isProblem something could not be done; drawn as a warning
 */
public record Notice(String text, boolean isProblem) {

    public Notice {
        Objects.requireNonNull(text, "text");
    }

    public static Notice info(String text) {
        return new Notice(text, false);
    }

    public static Notice problem(String text) {
        return new Notice(text, true);
    }
}
