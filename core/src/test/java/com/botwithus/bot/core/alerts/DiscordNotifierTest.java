package com.botwithus.bot.core.alerts;

import com.botwithus.bot.core.secrets.Secret;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordNotifierTest {

    private static final Secret HOOK = new Secret("https://discord.com/api/webhooks/1182/Xk9secret");
    private static final AlertMessage CRASH =
            new AlertMessage("Script crashed", "Fishing crashed on Hollowmere", true);
    private static final AlertMessage BACK =
            new AlertMessage("Client came back", "Hollowmere is back", false);

    private final ManualClock clock = new ManualClock(Instant.parse("2026-09-26T12:00:00Z"));
    private final ScriptedTransport transport = new ScriptedTransport(clock, Duration.ofMillis(50));
    private final HttpDelivery delivery =
            new HttpDelivery(transport, RetryPolicy.DEFAULT, transport.sleeper(), clock);

    private JsonObject sentJson() {
        return JsonParser.parseString(transport.onlyRequest().body()).getAsJsonObject();
    }

    private static List<String> parse(JsonObject json) {
        JsonArray parse = json.getAsJsonObject("allowed_mentions").getAsJsonArray("parse");
        return parse.asList().stream().map(element -> element.getAsString()).toList();
    }

    @Test
    void send_postsContentWithMentionsDisabled() {
        transport.thenReply(204, "");

        SendResult result = new DiscordNotifier(HOOK, false, delivery).send(CRASH);

        JsonObject json = sentJson();
        assertTrue(result.isDelivered());
        assertEquals("**Script crashed**\nFishing crashed on Hollowmere", json.get("content").getAsString());
        assertEquals(List.of(), parse(json), "allowed_mentions must be sent, allowing nothing");
        assertEquals("application/json; charset=utf-8", transport.onlyRequest().headers().get("Content-Type"));
    }

    @Test
    void send_problemWithMentionOn_startsWithHereAndAllowsOnlyThat() {
        transport.thenReply(204, "");

        new DiscordNotifier(HOOK, true, delivery).send(CRASH);

        JsonObject json = sentJson();
        assertTrue(json.get("content").getAsString().startsWith("@here **Script crashed**"));
        assertEquals(List.of("everyone"), parse(json));
    }

    @Test
    void send_goodNewsWithMentionOn_doesNotMention() {
        transport.thenReply(204, "");

        new DiscordNotifier(HOOK, true, delivery).send(BACK);

        JsonObject json = sentJson();
        assertFalse(json.get("content").getAsString().contains("@here"));
        assertEquals(List.of(), parse(json));
    }

    @Test
    void send_mentionsInsideTheText_areDefused() {
        transport.thenReply(204, "");

        new DiscordNotifier(HOOK, true, delivery)
                .send(new AlertMessage("Script crashed", "@everyone-bot crashed near @here", true));

        String content = sentJson().get("content").getAsString();
        assertTrue(content.startsWith("@here "), content);
        assertFalse(content.substring("@here ".length()).contains("@everyone"), content);
        assertFalse(content.substring("@here ".length()).contains("@here"), content);
    }

    @Test
    void send_longMessage_isCutToDiscordsLimit() {
        transport.thenReply(204, "");

        new DiscordNotifier(HOOK, false, delivery).send(new AlertMessage("T", "x".repeat(5000), false));

        String content = sentJson().get("content").getAsString();
        assertEquals(DiscordNotifier.MAX_CONTENT_CHARS, content.length());
        assertTrue(content.endsWith("…"));
    }

    @Test
    void send_unknownWebhook_readsDiscordsMessage() {
        transport.thenReply(401, "{\"message\": \"Unknown Webhook\", \"code\": 10015}");

        SendResult result = new DiscordNotifier(HOOK, false, delivery).send(CRASH);

        assertEquals("401 Unauthorized · Unknown Webhook", result.summary());
        assertEquals(1, transport.requests().size(), "a 401 is not retried");
    }

    @Test
    void send_rateLimited_waitsTheRetryAfterFromTheBody() {
        transport.thenReply(429, "{\"message\": \"You are being rate limited.\", \"retry_after\": 1.5, "
                + "\"global\": false}").thenReply(204, "");

        SendResult result = new DiscordNotifier(HOOK, false, delivery).send(CRASH);

        assertTrue(result.isDelivered());
        assertEquals(List.of(Duration.ofMillis(1500)), transport.sleeps());
    }

    @Test
    void send_rateLimited_fallsBackToTheRetryAfterHeader() {
        transport.then(() -> new HttpReply(429, Map.of("retry-after", "3"), "")).thenReply(204, "");

        new DiscordNotifier(HOOK, false, delivery).send(CRASH);

        assertEquals(List.of(Duration.ofSeconds(3)), transport.sleeps());
    }
}
