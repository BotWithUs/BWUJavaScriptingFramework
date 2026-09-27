package com.botwithus.bot.core.alerts;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What a {@link Notifier} sends: a title, a body and whether it reports a problem.
 * Each notifier lays these out in its service's own format.
 *
 * @param title     one short line
 * @param body      one or more lines; never empty
 * @param isProblem whether something went wrong (ntfy priority, Discord mention)
 */
public record AlertMessage(String title, String body, boolean isProblem) {

    /** Lines listed in a burst message before the rest are counted as "and N more". */
    public static final int MAX_BURST_LINES = 20;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final String NEWLINE = "\n";

    public AlertMessage {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        if (body.isBlank()) {
            throw new IllegalArgumentException("a message needs a body");
        }
    }

    /** The message for one alert: the kind's title over the headline and any detail. */
    public static AlertMessage of(Alert alert) {
        String body = alert.detail().isEmpty() ? alert.headline() : alert.headline() + NEWLINE + alert.detail();
        return new AlertMessage(alert.kind().title(), body, alert.kind().isProblem());
    }

    /**
     * The message for a burst: the alert's own message for a burst of one, else one
     * line per alert with its local time.
     *
     * @param zone the zone the times are shown in
     */
    public static AlertMessage of(Burst burst, ZoneId zone) {
        if (burst.total() == 1) {
            return of(burst.alerts().getFirst());
        }
        List<String> lines = new ArrayList<>();
        List<Alert> listed = burst.alerts().subList(0, Math.min(MAX_BURST_LINES, burst.alerts().size()));
        for (Alert alert : listed) {
            lines.add(TIME.format(alert.at().atZone(zone)) + " " + alert.headline());
        }
        int unlisted = burst.total() - listed.size();
        if (unlisted > 0) {
            lines.add("and " + unlisted + " more");
        }
        boolean isProblem = burst.alerts().stream().anyMatch(alert -> alert.kind().isProblem());
        return new AlertMessage(burst.total() + " alerts", String.join(NEWLINE, lines), isProblem);
    }
}
