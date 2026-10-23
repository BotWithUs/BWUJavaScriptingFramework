package com.botwithus.bot.cli.gui.pages.dashboard;

import java.time.Instant;
import java.util.Objects;

/**
 * One line of the Events tab: a host event, described.
 *
 * @param type        the event's kind, e.g. {@code ScriptCrashed}
 * @param clientLabel the client it is about, or "-" when host-wide
 * @param detail      what happened, on one line
 */
public record EventRow(Instant at, String type, String clientLabel, String detail) {

    public EventRow {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(clientLabel, "clientLabel");
        Objects.requireNonNull(detail, "detail");
    }
}
