package com.botwithus.bot.cli.gui.pages.dashboard;

import java.util.Objects;

/**
 * One entry of a client picker: the scope it selects and how it reads.
 *
 * @param label the client as the user knows it, or "All clients (N)"
 * @param note  what is wrong with it right now, such as "not responding", or empty
 */
public record ScopeOption(Scope scope, String label, String note) {

    public ScopeOption {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(note, "note");
    }

    /** An entry with nothing wrong. */
    public ScopeOption(Scope scope, String label) {
        this(scope, label, "");
    }

    /** How the picker shows it: the label, then the note after a dot. */
    public String display() {
        return note.isEmpty() ? label : label + " · " + note;
    }
}
