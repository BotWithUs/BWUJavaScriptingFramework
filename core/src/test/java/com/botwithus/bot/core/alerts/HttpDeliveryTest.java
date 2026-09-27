package com.botwithus.bot.core.alerts;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class HttpDeliveryTest {

    private static final Instant T0 = Instant.parse("2026-09-26T12:00:00Z");
    private static final Duration ROUND_TRIP = Duration.ofMillis(180);
    private static final HttpPost REQUEST = new HttpPost(
            URI.create("https://discord.com/api/webhooks/1182/Xk9secret"), Map.of(), "{}");
    private static final RateLimitReader FIVE_SECONDS = reply -> Optional.of(Duration.ofSeconds(5));

    private final ManualClock clock = new ManualClock(T0);
    private final ScriptedTransport transport = new ScriptedTransport(clock, ROUND_TRIP);
    private final HttpDelivery delivery =
            new HttpDelivery(transport, RetryPolicy.DEFAULT, transport.sleeper(), clock);

    @Test
    void success_firstTry_isDeliveredWithItsRoundTrip() {
        transport.thenReply(204, "");

        SendResult result = delivery.post(REQUEST, RateLimitReader.NONE);

        assertEquals(new SendResult.Delivered(T0.plus(ROUND_TRIP), ROUND_TRIP, 1), result);
        assertEquals(List.of(), transport.sleeps());
    }

    @Test
    void serverError_isRetriedWithBackOff_thenDelivered() {
        transport.thenReply(502, "").thenReply(503, "").thenReply(200, "ok");

        SendResult result = delivery.post(REQUEST, RateLimitReader.NONE);

        assertTrue(result.isDelivered());
        assertEquals(3, result.attempts());
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)), transport.sleeps());
    }

    @Test
    void serverError_everyTime_failsAfterTheLastAttempt() {
        transport.thenReply(500, "").thenReply(500, "").thenReply(503, "down for maintenance");

        SendResult result = delivery.post(REQUEST, RateLimitReader.NONE);

        assertEquals(new SendResult.Failed(clock.instant(), "503 Service Unavailable",
                "down for maintenance", 3), result);
        assertEquals(3, transport.requests().size());
        assertEquals(2, transport.sleeps().size(), "no wait after the last attempt");
    }

    @Test
    void clientError_isNotRetried() {
        transport.thenReply(401, "{\"message\": \"Unknown Webhook\", \"code\": 10015}");

        SendResult result = delivery.post(REQUEST, RateLimitReader.NONE);

        assertEquals("401 Unauthorized · Unknown Webhook", result.summary());
        assertEquals(1, transport.requests().size());
    }

    @Test
    void rateLimited_withAReadableWait_waitsItOutAndRetries() {
        transport.thenReply(429, "{}").thenReply(204, "");

        SendResult result = delivery.post(REQUEST, FIVE_SECONDS);

        assertTrue(result.isDelivered());
        assertEquals(List.of(Duration.ofSeconds(5)), transport.sleeps());
    }

    @Test
    void rateLimited_withoutAReader_failsAtOnce() {
        transport.thenReply(429, "slow down");

        SendResult result = delivery.post(REQUEST, RateLimitReader.NONE);

        assertEquals("429 Too Many Requests · slow down", result.summary());
        assertEquals(1, transport.requests().size());
    }

    @Test
    void rateLimited_longerThanThePolicyAllows_failsWithoutWaiting() {
        transport.thenReply(429, "{}");
        RateLimitReader tenMinutes = reply -> Optional.of(Duration.ofMinutes(10));

        SendResult result = delivery.post(REQUEST, tenMinutes);

        assertEquals("429 Too Many Requests · Asked to wait 600 s", result.summary());
        assertEquals(List.of(), transport.sleeps());
    }

    @Test
    void rateLimited_countsAsAnAttempt() {
        transport.thenReply(429, "{}").thenReply(429, "{}").thenReply(429, "{}");

        SendResult result = delivery.post(REQUEST, FIVE_SECONDS);

        assertFalse(result.isDelivered());
        assertEquals(3, transport.requests().size());
    }

    @Test
    void timeout_isRetried_andTheReasonNamesOnlyTheHost() {
        transport.then(() -> {
            throw new HttpTimeoutException("request to " + REQUEST.uri() + " timed out");
        }).then(() -> {
            throw new HttpTimeoutException("again");
        }).then(() -> {
            throw new HttpTimeoutException("and again");
        });

        SendResult result = delivery.post(REQUEST, RateLimitReader.NONE);

        assertEquals("Timed out · discord.com/…", result.summary());
        assertEquals(3, result.attempts());
    }

    @Test
    void connectFailure_thenSuccess_isDelivered() {
        transport.then(() -> {
            throw new ConnectException();
        }).thenReply(204, "");

        assertTrue(delivery.post(REQUEST, RateLimitReader.NONE).isDelivered());
    }

    @Test
    void connectFailure_everyTime_saysSoWithoutTheUrl() {
        for (int i = 0; i < RetryPolicy.DEFAULT.maxAttempts(); i++) {
            transport.then(() -> {
                throw new ConnectException("Connection refused: " + REQUEST.uri());
            });
        }

        assertEquals("Could not connect · discord.com/…", delivery.post(REQUEST, RateLimitReader.NONE).summary());
    }

    @Test
    void tlsFailure_isNotRetried() {
        transport.then(() -> {
            throw new SSLHandshakeException("PKIX path building failed");
        });

        SendResult result = delivery.post(REQUEST, RateLimitReader.NONE);

        assertEquals("Secure connection failed · discord.com/…", result.summary());
        assertEquals(1, transport.requests().size());
    }

    @Test
    void redirect_isNotFollowedOrRetried() {
        transport.thenReply(301, "");

        assertEquals("301 Moved Permanently", delivery.post(REQUEST, RateLimitReader.NONE).summary());
    }

    @Test
    void serverReason_withAUrlInIt_isRedacted() {
        transport.thenReply(404, "no such hook: https://discord.com/api/webhooks/1182/Xk9secret");

        String summary = delivery.post(REQUEST, RateLimitReader.NONE).summary();

        assertAll(
                () -> assertEquals("404 Not Found · no such hook: discord.com/…", summary),
                () -> assertFalse(summary.contains("Xk9secret")));
    }

    @Test
    void serverReason_htmlOrLong_isShortened() {
        transport.thenReply(400, "<html><body>Bad</body></html>");

        assertEquals("400 Bad Request", delivery.post(REQUEST, RateLimitReader.NONE).summary());
    }

    @Test
    void serverReason_long_isCutToOneShortLine() {
        transport.thenReply(400, "x".repeat(500) + "\nsecond line");

        String reason = switch (delivery.post(REQUEST, RateLimitReader.NONE)) {
            case SendResult.Failed failed -> failed.reason();
            case SendResult.Delivered delivered -> fail("a 400 was delivered");
        };

        assertEquals(ServerReason.MAX_CHARS, reason.length());
        assertTrue(reason.endsWith("…"));
    }

    @Test
    void interrupted_stopsAndSaysCancelled() {
        transport.then(() -> {
            throw new InterruptedException();
        });

        SendResult result = delivery.post(REQUEST, RateLimitReader.NONE);

        assertEquals("Cancelled", result.summary());
        assertTrue(Thread.interrupted(), "the interrupt is kept for the caller");
    }
}
