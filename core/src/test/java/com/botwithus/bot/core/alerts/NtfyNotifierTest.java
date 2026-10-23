package com.botwithus.bot.core.alerts;

import com.botwithus.bot.core.secrets.Secret;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NtfyNotifierTest {

    private static final URI NTFY_SH = URI.create("https://ntfy.sh");
    private static final AlertMessage CRASH =
            new AlertMessage("Script crashed", "Fishing crashed on Hollowmere", true);

    private final ManualClock clock = new ManualClock(Instant.parse("2026-09-26T12:00:00Z"));
    private final ScriptedTransport transport = new ScriptedTransport(clock, Duration.ofMillis(50));
    private final HttpDelivery delivery =
            new HttpDelivery(transport, RetryPolicy.DEFAULT, transport.sleeper(), clock);

    @Test
    void send_postsTheBodyToServerSlashTopic() {
        transport.thenReply(200, "{\"id\":\"x\"}");

        SendResult result = new NtfyNotifier(NTFY_SH, "bwu-alerts-7f3k", Optional.empty(), delivery).send(CRASH);

        HttpPost post = transport.onlyRequest();
        assertAll(
                () -> assertTrue(result.isDelivered()),
                () -> assertEquals(URI.create("https://ntfy.sh/bwu-alerts-7f3k"), post.uri()),
                () -> assertEquals("Fishing crashed on Hollowmere", post.body()),
                () -> assertEquals("Script crashed", post.headers().get("Title")),
                () -> assertEquals("high", post.headers().get("Priority")),
                () -> assertEquals("warning", post.headers().get("Tags")),
                () -> assertFalse(post.headers().containsKey("Authorization")));
    }

    @Test
    void send_serverWithTrailingSlashOrPath_joinsCleanly() {
        transport.thenReply(200, "{}").thenReply(200, "{}");

        new NtfyNotifier(URI.create("https://ntfy.sh/"), "t", Optional.empty(), delivery).send(CRASH);
        new NtfyNotifier(URI.create("http://nas.local:8080/ntfy"), "t", Optional.empty(), delivery).send(CRASH);

        assertEquals(URI.create("https://ntfy.sh/t"), transport.requests().get(0).uri());
        assertEquals(URI.create("http://nas.local:8080/ntfy/t"), transport.requests().get(1).uri());
    }

    @Test
    void send_withToken_addsABearerHeader() {
        transport.thenReply(200, "{}");

        new NtfyNotifier(NTFY_SH, "topic", Optional.of(new Secret("tk_abc")), delivery).send(CRASH);

        assertEquals("Bearer tk_abc", transport.onlyRequest().headers().get("Authorization"));
    }

    @Test
    void send_goodNews_isDefaultPriorityWithNoTag() {
        transport.thenReply(200, "{}");

        new NtfyNotifier(NTFY_SH, "topic", Optional.empty(), delivery)
                .send(new AlertMessage("Client came back", "Hollowmere is back", false));

        assertEquals("default", transport.onlyRequest().headers().get("Priority"));
        assertFalse(transport.onlyRequest().headers().containsKey("Tags"));
    }

    @Test
    void send_nonAsciiTitle_isMadeHeaderSafe() {
        transport.thenReply(200, "{}");

        new NtfyNotifier(NTFY_SH, "topic", Optional.empty(), delivery)
                .send(new AlertMessage("Daily summary · Björn ✓", "body", false));

        assertEquals("Daily summary - Bj?rn ?", transport.onlyRequest().headers().get("Title"));
    }

    @Test
    void send_unauthorized_reportsNtfysError() {
        transport.thenReply(401, "{\"code\":40101,\"http\":401,\"error\":\"unauthorized\"}");

        SendResult result = new NtfyNotifier(NTFY_SH, "topic", Optional.empty(), delivery).send(CRASH);

        assertEquals("401 Unauthorized · unauthorized", result.summary());
    }

    @Test
    void badTopicOrServer_isRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new NtfyNotifier(NTFY_SH, "has space", Optional.empty(), delivery));
        assertThrows(IllegalArgumentException.class,
                () -> new NtfyNotifier(URI.create("ftp://ntfy.sh"), "t", Optional.empty(), delivery));
    }
}
