package com.botwithus.bot.core.alerts;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SendResultTest {

    private static final Instant T0 = Instant.parse("2026-09-26T12:00:00Z");

    @Test
    void failed_summaryIsStatusDotReason() {
        assertEquals("401 Unauthorized · Unknown Webhook",
                new SendResult.Failed(T0, "401 Unauthorized", "Unknown Webhook", 1).summary());
    }

    @Test
    void failed_withoutReason_isJustTheStatus() {
        assertEquals("503 Service Unavailable",
                new SendResult.Failed(T0, "503 Service Unavailable", "", 3).summary());
    }

    @Test
    void delivered_summaryGivesTheTimeTaken() {
        assertEquals("Delivered · 180 ms",
                new SendResult.Delivered(T0, Duration.ofMillis(180), 1).summary());
    }
}
