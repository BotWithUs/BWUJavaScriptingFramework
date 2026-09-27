package com.botwithus.bot.core.alerts;

import com.botwithus.bot.core.secrets.Secret;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlackNotifierTest {

    private static final Secret HOOK = new Secret("https://hooks.slack.com/services/T0/B0/secret");

    private final ManualClock clock = new ManualClock(Instant.parse("2026-09-26T12:00:00Z"));
    private final ScriptedTransport transport = new ScriptedTransport(clock, Duration.ofMillis(50));
    private final HttpDelivery delivery =
            new HttpDelivery(transport, RetryPolicy.DEFAULT, transport.sleeper(), clock);

    @Test
    void send_postsTextJsonToTheWebhook() {
        transport.thenReply(200, "ok");

        SendResult result = new SlackNotifier(HOOK, delivery)
                .send(new AlertMessage("Script crashed", "Fishing crashed on Hollowmere", true));

        HttpPost post = transport.onlyRequest();
        JsonObject json = JsonParser.parseString(post.body()).getAsJsonObject();
        assertTrue(result.isDelivered());
        assertEquals(URI.create(HOOK.reveal()), post.uri());
        assertEquals("application/json; charset=utf-8", post.headers().get("Content-Type"));
        assertEquals("*Script crashed*\nFishing crashed on Hollowmere", json.get("text").getAsString());
        assertEquals(1, json.size(), "only text");
    }

    @Test
    void send_escapesSlackControlCharacters() {
        transport.thenReply(200, "ok");

        new SlackNotifier(HOOK, delivery).send(new AlertMessage("T", "a <b> & c", false));

        String text = JsonParser.parseString(transport.onlyRequest().body()).getAsJsonObject()
                .get("text").getAsString();
        assertEquals("*T*\na &lt;b&gt; &amp; c", text);
    }

    @Test
    void send_slackError_isTheSummaryReason() {
        transport.thenReply(404, "no_service");

        SendResult result = new SlackNotifier(HOOK, delivery).send(new AlertMessage("T", "b", false));

        assertEquals("404 Not Found · no_service", result.summary());
    }

    @Test
    void nonHttpsWebhook_isRefusedWithoutEchoingIt() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SlackNotifier(new Secret("http://hooks.slack.com/services/T0/B0/secret"), delivery));

        assertFalse(e.getMessage().contains("secret"), e.getMessage());
    }
}
