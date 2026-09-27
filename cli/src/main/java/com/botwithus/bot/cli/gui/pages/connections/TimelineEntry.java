package com.botwithus.bot.cli.gui.pages.connections;

import java.time.Instant;
import java.util.Objects;

/**
 * One line of a client's history in the detail pane.
 *
 * @param at     when it happened
 * @param tone   the dot's colour
 * @param lead   the words drawn in the text colour, such as "Connection lost"
 * @param detail the rest, drawn dimmer; empty for none
 */
public record TimelineEntry(Instant at, Tone tone, String lead, String detail) {

    public TimelineEntry {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(tone, "tone");
        Objects.requireNonNull(lead, "lead");
        Objects.requireNonNull(detail, "detail");
    }
}
