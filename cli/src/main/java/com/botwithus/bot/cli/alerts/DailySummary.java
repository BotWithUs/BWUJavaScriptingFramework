package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.cli.events.ConnectionHistory;
import com.botwithus.bot.cli.events.HostEvent;
import com.botwithus.bot.core.alerts.Alert;
import com.botwithus.bot.core.alerts.AlertKind;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Words the daily summary: who is online and for how long, and what went wrong on
 * each client in the last 24 hours.
 *
 * <pre>
 * 1 of 3 clients online
 * Ashvale · closed · 1 drop
 * Hollowmere · online 6h 12m · 2 scripts running · 1 crash, 2 stalls
 * Ravenmoor · not responding · 1 drop
 * Host · 1 JAR failed to load
 * </pre>
 *
 * <p>A closed client is listed only if something happened to it in the last 24
 * hours, so clients remembered from long ago do not fill every summary.</p>
 */
public final class DailySummary {

    /** How far back the summary looks. */
    public static final Duration WINDOW = Duration.ofDays(1);

    private static final String DOT = " · ";

    /** What went wrong in the window, per kind. */
    private record Tally(int crashes, int stalls, int drops, int loadFailures, int managementCrashes) {

        static Tally of(List<HostEvent> events, Instant since) {
            int crashes = 0;
            int stalls = 0;
            int drops = 0;
            int loadFailures = 0;
            int managementCrashes = 0;
            for (HostEvent event : events) {
                if (event.at().isBefore(since)) {
                    continue;
                }
                switch (event) {
                    case HostEvent.ScriptCrashed _ -> crashes++;
                    case HostEvent.ScriptStalled _ -> stalls++;
                    case HostEvent.ConnectionLost _ -> drops++;
                    case HostEvent.ScriptLoadFailed _ -> loadFailures++;
                    case HostEvent.ManagementScriptCrashed _ -> managementCrashes++;
                    default -> { }
                }
            }
            return new Tally(crashes, stalls, drops, loadFailures, managementCrashes);
        }

        String clientPart() {
            List<String> parts = new ArrayList<>();
            addCount(parts, crashes, "crash", "crashes");
            addCount(parts, stalls, "stall", "stalls");
            addCount(parts, drops, "drop", "drops");
            return parts.isEmpty() ? "" : DOT + String.join(", ", parts);
        }

        List<String> hostParts() {
            List<String> parts = new ArrayList<>();
            addCount(parts, loadFailures, "JAR failed to load", "JARs failed to load");
            addCount(parts, managementCrashes, "management script crash", "management script crashes");
            return parts;
        }
    }

    private DailySummary() {
    }

    /**
     * The summary alert as of {@code now}.
     *
     * @param clients every client the host knows now
     * @param history the host's event history, for the last day's problems
     */
    public static Alert compose(Instant now, List<ClientSnapshot> clients, ConnectionHistory history) {
        Instant since = now.minus(WINDOW);
        List<ClientSnapshot> listed = clients.stream()
                .filter(client -> client.state() != ClientSnapshot.State.CLOSED
                        || hasEventSince(history.forClient(client.key()), since))
                .sorted(Comparator.comparing(ClientSnapshot::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        List<String> detail = new ArrayList<>();
        for (ClientSnapshot client : listed) {
            detail.add(client.name() + DOT + stateText(client, now)
                    + Tally.of(history.forClient(client.key()), since).clientPart());
        }
        List<String> hostParts = Tally.of(history.hostWide(), since).hostParts();
        if (!hostParts.isEmpty()) {
            detail.add("Host" + DOT + String.join(", ", hostParts));
        }
        return new Alert(AlertKind.DAILY_SUMMARY, headline(listed), String.join("\n", detail), now);
    }

    private static boolean hasEventSince(List<HostEvent> events, Instant since) {
        return events.stream().anyMatch(event -> !event.at().isBefore(since));
    }

    private static String stateText(ClientSnapshot client, Instant now) {
        return switch (client.state()) {
            case ONLINE -> "online " + uptime(Duration.between(client.connectedAt().orElse(now), now)) + DOT
                    + count(client.runningScripts(), "script running", "scripts running");
            case NOT_RESPONDING -> "not responding";
            case CLOSED -> "closed";
        };
    }

    private static String headline(List<ClientSnapshot> listed) {
        if (listed.isEmpty()) {
            return "No clients connected";
        }
        long online = listed.stream().filter(client -> client.state() == ClientSnapshot.State.ONLINE).count();
        return online + " of " + listed.size() + " clients online";
    }

    /** {@code 3d 4h}, {@code 6h 12m} or {@code 45m}. */
    static String uptime(Duration duration) {
        Duration positive = duration.isNegative() ? Duration.ZERO : duration;
        if (positive.toDays() > 0) {
            return positive.toDays() + "d " + positive.toHoursPart() + "h";
        }
        if (positive.toHours() > 0) {
            return positive.toHours() + "h " + positive.toMinutesPart() + "m";
        }
        return positive.toMinutes() + "m";
    }

    private static void addCount(List<String> parts, int n, String one, String many) {
        if (n > 0) {
            parts.add(count(n, one, many));
        }
    }

    private static String count(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }
}
