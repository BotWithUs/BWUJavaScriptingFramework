package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.core.alerts.SendResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LastSendTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Instant AT = Instant.parse("2026-09-26T12:02:00Z");

    @Test
    void failedTest_isStatusDotReasonWithTheTime() {
        LastSend send = LastSend.test(new SendResult.Failed(AT, "401 Unauthorized", "Unknown Webhook", 1));

        assertEquals("401 Unauthorized · Unknown Webhook (14:02)", send.line(BERLIN));
    }

    @Test
    void deliveredTest_saysDeliveredWithTimeAndRoundTrip() {
        LastSend send = LastSend.test(new SendResult.Delivered(AT, Duration.ofMillis(180), 1));

        assertEquals("Test delivered 14:02 · 180 ms", send.line(BERLIN));
    }

    @Test
    void deliveredAlert_saysWhatWasSent() {
        LastSend send = LastSend.alert(new SendResult.Delivered(AT, Duration.ofMillis(95), 1),
                "Hollowmere stopped responding");

        assertEquals("Last sent 14:02 · Hollowmere stopped responding", send.line(BERLIN));
    }

    @Test
    void failedAlert_isStatusDotReasonWithTheTime() {
        LastSend send = LastSend.alert(new SendResult.Failed(AT, "Timed out", "discord.com/…", 3), "x");

        assertEquals("Timed out · discord.com/… (14:02)", send.line(BERLIN));
    }
}
