package com.botwithus.bot.cli.gui.pages.connections;

import java.util.Objects;

/**
 * A script on a client, as the detail pane lists it.
 *
 * @param name the script's name
 * @param tone running, stalled, crashed, or neither
 */
public record ScriptChip(String name, Tone tone) {

    public ScriptChip {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(tone, "tone");
    }
}
