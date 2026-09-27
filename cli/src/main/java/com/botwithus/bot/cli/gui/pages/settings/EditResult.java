package com.botwithus.bot.cli.gui.pages.settings;

/** What happened to one edit made on the page. */
public sealed interface EditResult {

    /** The value was taken and is being saved. */
    record Applied() implements EditResult {}

    /**
     * The value was refused; the setting keeps its old value.
     *
     * @param message the rule it broke, written to show under the control
     */
    record Refused(String message) implements EditResult {}
}
