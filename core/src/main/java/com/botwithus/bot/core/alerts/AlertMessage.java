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
    private static final String HELD_TITLE_SUFFIX = " during quiet hours";
    private static final String NOT_LISTED = " more, not listed";

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
        return new AlertMessage(burst.total() + " alerts", timedLines(burst.alerts(), burst.total(), zone),
                isAnyProblem(burst.alerts()));
    }

    /**
     * The message sent when quiet hours end, for what they held back: one line per
     * alert with its local time, oldest first, the first {@link #MAX_BURST_LINES}
     * listed and the rest counted. Even one held alert is sent in this form, so the
     * title says it is late.
     *
     * @param held     the alerts kept, oldest first
     * @param overflow how many more were held than kept
     * @param zone     the zone the times are shown in
     * @throws IllegalArgumentException if there is nothing to report
     */
    public static AlertMessage ofHeld(List<Alert> held, int overflow, ZoneId zone) {
        int total = held.size() + overflow;
        if (total <= 0) {
            throw new IllegalArgumentException("nothing was held");
        }
        String title = total + (total == 1 ? " alert" : " alerts") + HELD_TITLE_SUFFIX;
        String body = held.isEmpty() ? overflow + NOT_LISTED : timedLines(held, total, zone);
        return new AlertMessage(title, body, isAnyProblem(held));
    }

    private static String timedLines(List<Alert> alerts, int total, ZoneId zone) {
        List<String> lines = new ArrayList<>();
        List<Alert> listed = alerts.subList(0, Math.min(MAX_BURST_LINES, alerts.size()));
        for (Alert alert : listed) {
            lines.add(TIME.format(alert.at().atZone(zone)) + " " + alert.headline());
        }
        int unlisted = total - listed.size();
        if (unlisted > 0) {
            lines.add("and " + unlisted + " more");
        }
        return String.join(NEWLINE, lines);
    }

    private static boolean isAnyProblem(List<Alert> alerts) {
        return alerts.stream().anyMatch(alert -> alert.kind().isProblem());
    }
}
