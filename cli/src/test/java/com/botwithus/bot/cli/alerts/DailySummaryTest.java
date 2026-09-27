package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.cli.alerts.ClientSnapshot.State;
import com.botwithus.bot.cli.events.ClientRef;
import com.botwithus.bot.cli.events.ConnectionHistory;
import com.botwithus.bot.cli.events.HostEvent;
import com.botwithus.bot.core.alerts.Alert;
import com.botwithus.bot.core.alerts.AlertKind;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DailySummaryTest {

    private static final Instant NOW = Instant.parse("2026-09-26T22:00:00Z");
    private static final ClientRef HOLLOW = new ClientRef("BotWithUs_1");
    private static final ClientRef RAVEN = new ClientRef("BotWithUs_2");
    private static final ClientRef ASH = new ClientRef("BotWithUs_3");
    private static final ClientRef OLD = new ClientRef("BotWithUs_4");

    private final ConnectionHistory history = new ConnectionHistory();

    private static LastCrash crash() {
        return new LastCrash(Phase.ON_LOOP, 1, NOW, new IllegalStateException());
    }

    private static Instant hoursAgo(long hours) {
        return NOW.minus(Duration.ofHours(hours));
    }

    private static ClientSnapshot online(ClientRef ref, String name, Duration up, int scripts) {
        return new ClientSnapshot(ref.key(), name, State.ONLINE, Optional.of(NOW.minus(up)), scripts);
    }

    private static ClientSnapshot closed(ClientRef ref, String name) {
        return new ClientSnapshot(ref.key(), name, State.CLOSED, Optional.empty(), 0);
    }

    @Test
    void compose_listsEachClientWithUptimeScriptsAndTheDaysProblems() {
        history.accept(new HostEvent.ScriptCrashed(HOLLOW, "Fishing", crash(), hoursAgo(3)));
        history.accept(new HostEvent.ScriptStalled(HOLLOW, "Fishing", hoursAgo(2)));
        history.accept(new HostEvent.ScriptStalled(HOLLOW, "Fishing", hoursAgo(1)));
        history.accept(new HostEvent.ScriptCrashed(HOLLOW, "Fishing", crash(), hoursAgo(30)));
        history.accept(new HostEvent.ConnectionLost(RAVEN, null, hoursAgo(1)));
        history.accept(new HostEvent.ConnectionLost(ASH, null, hoursAgo(5)));
        history.accept(new HostEvent.ClientClosed(ASH, HostEvent.CloseCause.CONNECTION_LOST, hoursAgo(5)));
        history.accept(new HostEvent.ClientClosed(OLD, HostEvent.CloseCause.CONNECTION_LOST, hoursAgo(40)));
        history.accept(new HostEvent.ScriptLoadFailed(Path.of("a.jar"), new IllegalStateException(), hoursAgo(4)));
        List<ClientSnapshot> clients = List.of(
                new ClientSnapshot(RAVEN.key(), "Ravenmoor", State.NOT_RESPONDING,
                        Optional.of(hoursAgo(9)), 0),
                online(HOLLOW, "Hollowmere", Duration.ofHours(6).plusMinutes(12), 2),
                closed(ASH, "Ashvale"),
                closed(OLD, "Oldtown"));

        Alert summary = DailySummary.compose(NOW, clients, history);

        assertEquals(AlertKind.DAILY_SUMMARY, summary.kind());
        assertEquals(NOW, summary.at());
        assertEquals("1 of 3 clients online", summary.headline(), "Oldtown closed two days ago: not listed");
        assertEquals(String.join("\n",
                "Ashvale · closed · 1 drop",
                "Hollowmere · online 6h 12m · 2 scripts running · 1 crash, 2 stalls",
                "Ravenmoor · not responding · 1 drop",
                "Host · 1 JAR failed to load"), summary.detail());
    }

    @Test
    void compose_longUptime_isInDaysAndHours() {
        Alert summary = DailySummary.compose(NOW,
                List.of(online(HOLLOW, "Hollowmere", Duration.ofDays(3).plusHours(4).plusMinutes(9), 1)), history);

        assertEquals("1 of 1 clients online", summary.headline());
        assertEquals("Hollowmere · online 3d 4h · 1 script running", summary.detail());
    }

    @Test
    void compose_closedClientWithNothingRecent_isLeftOut() {
        Alert summary = DailySummary.compose(NOW, List.of(closed(OLD, "Oldtown")), history);

        assertEquals("No clients connected", summary.headline());
        assertEquals("", summary.detail());
    }

    @Test
    void compose_noClients_saysSo() {
        Alert summary = DailySummary.compose(NOW, List.of(), history);

        assertEquals("No clients connected", summary.headline());
        assertEquals("", summary.detail());
    }
}
