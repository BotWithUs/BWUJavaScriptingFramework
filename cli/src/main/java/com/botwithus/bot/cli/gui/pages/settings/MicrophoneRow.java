package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.gui.SafeText;

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

    /** The longest key shown; its middle gives way, so the module and the class's end stay. */
    static final int MAX_KEY_CHARS = 160;

    public MicrophoneRow {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(name, "name");
    }

    /** The key as the table shows it: one clean line, with the module and the class's end kept. */
    public String shownKey() {
        return SafeText.key(key, MAX_KEY_CHARS);
    }

    /** The decision as the table shows it. */
    public String decisionLabel() {
        return isAllowed ? "Allowed" : "Denied";
    }

    String findText() {
        return (name + " " + key + " " + decisionLabel()).toLowerCase(Locale.ROOT);
    }
}
