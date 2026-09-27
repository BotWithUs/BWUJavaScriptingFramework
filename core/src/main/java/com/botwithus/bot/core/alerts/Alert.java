package com.botwithus.bot.core.alerts;

import java.time.Instant;
import java.util.Objects;

/**
 * One thing worth telling the user about, already worded.
 *
 * <p>Alerts name clients by their display name only — never an account id, a
 * pipe path or a file path — because they leave the machine.</p>
 *
 * @param kind     what the alert is about
 * @param headline one line, e.g. {@code Hollowmere stopped responding}
 * @param detail   further lines, or empty
 * @param at       when it happened
 */
public record Alert(AlertKind kind, String headline, String detail, Instant at) {

    public Alert {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(headline, "headline");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(at, "at");
    }

    /** An alert with no further lines. */
    public Alert(AlertKind kind, String headline, Instant at) {
        this(kind, headline, "", at);
    }
}
