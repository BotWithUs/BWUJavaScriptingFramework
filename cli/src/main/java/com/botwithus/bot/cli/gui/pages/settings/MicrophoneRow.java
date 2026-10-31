package com.botwithus.bot.cli.gui.pages.settings;

import java.util.Locale;
import java.util.Objects;

/**
 * One script with a remembered microphone decision.
 *
 * @param key       the module and class the decision is kept under; what Reset forgets
 * @param name      the name the script had when the user decided
 * @param isAllowed whether the user allowed it; otherwise they denied it
 */
public record MicrophoneRow(String key, String name, boolean isAllowed) {

    public MicrophoneRow {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(name, "name");
    }

    /** The decision as the table shows it. */
    public String decisionLabel() {
        return isAllowed ? "Allowed" : "Denied";
    }

    String findText() {
        return (name + " " + key + " " + decisionLabel()).toLowerCase(Locale.ROOT);
    }
}
