package com.botwithus.bot.core.alerts;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertMessageTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final Instant T0 = Instant.parse("2026-09-26T12:05:00Z");

    @Test
    void of_oneAlert_titleIsTheKindAndBodyTheHeadline() {
        AlertMessage message = AlertMessage.of(
                new Alert(AlertKind.CLIENT_LOST, "Hollowmere stopped responding", T0));

        assertEquals(new AlertMessage("Client stopped responding", "Hollowmere stopped responding", true),
                message);
    }

    @Test
    void of_alertWithDetail_addsTheDetailBelowTheHeadline() {
        AlertMessage message = AlertMessage.of(new Alert(AlertKind.DAILY_SUMMARY,
                "2 of 3 clients online", "Hollowmere · online 6h 12m\nRavenmoor · closed", T0));

        assertEquals("2 of 3 clients online\nHollowmere · online 6h 12m\nRavenmoor · closed", message.body());
        assertFalse(message.isProblem());
    }

    @Test
    void of_burstOfOne_isThatAlertsOwnMessage() {
        Alert alert = new Alert(AlertKind.SCRIPT_STALL, "Fishing stalled on Hollowmere", T0);

        assertEquals(AlertMessage.of(alert), AlertMessage.of(Burst.of(alert), ZONE));
    }

    @Test
    void of_burst_listsEachAlertWithItsLocalTime() {
        Burst burst = new Burst(List.of(
                new Alert(AlertKind.CLIENT_LOST, "Hollowmere stopped responding", T0),
                new Alert(AlertKind.CLIENT_BACK, "Ravenmoor is back", T0.plusSeconds(60))), 0);

        AlertMessage message = AlertMessage.of(burst, ZONE);

        assertEquals("2 alerts", message.title());
        assertEquals("14:05 Hollowmere stopped responding\n14:06 Ravenmoor is back", message.body());
        assertTrue(message.isProblem(), "one problem makes the burst a problem");
    }

    @Test
    void of_burstOfOnlyGoodNews_isNotAProblem() {
        Burst burst = new Burst(List.of(
                new Alert(AlertKind.CLIENT_BACK, "A is back", T0),
                new Alert(AlertKind.CLIENT_BACK, "B is back", T0)), 0);

        assertFalse(AlertMessage.of(burst, ZONE).isProblem());
    }

    @Test
    void of_longBurst_listsTheFirstLinesAndCountsTheRest() {
        List<Alert> alerts = new ArrayList<>();
        int count = AlertMessage.MAX_BURST_LINES + 5;
        for (int i = 0; i < count; i++) {
            alerts.add(new Alert(AlertKind.CLIENT_LOST, "C" + i + " stopped responding", T0));
        }

        AlertMessage message = AlertMessage.of(new Burst(alerts, 2), ZONE);
        List<String> lines = message.body().lines().toList();

        assertEquals((count + 2) + " alerts", message.title());
        assertEquals(AlertMessage.MAX_BURST_LINES + 1, lines.size());
        assertEquals("and 7 more", lines.getLast());
    }
}
