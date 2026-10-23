package com.botwithus.bot.core.alerts;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedactionTest {

    @Test
    void url_keepsOnlyTheHost() {
        assertEquals("discord.com/…",
                Redaction.url(URI.create("https://discord.com/api/webhooks/1182/Xk9secret")));
    }

    @Test
    void url_keepsAnExplicitPortButNotUserInfo() {
        assertEquals("ntfy.example.org:8080/…",
                Redaction.url(URI.create("http://alice:pw@ntfy.example.org:8080/my-topic?x=1")));
    }

    @Test
    void url_withNoHost_isJustTheEllipsis() {
        assertEquals("…", Redaction.url(URI.create("mailto:someone@example.org")));
    }

    @Test
    void scrub_replacesEveryUrlInText() {
        String text = "POST https://hooks.slack.com/services/T0/B0/secret failed; "
                + "retry http://user:pw@10.0.0.2:81/topic?token=abc later";

        assertEquals("POST hooks.slack.com/… failed; retry 10.0.0.2:81/… later",
                Redaction.scrub(text));
    }

    @Test
    void scrub_leavesTextWithoutUrlsAlone() {
        assertEquals("Unknown Webhook", Redaction.scrub("Unknown Webhook"));
    }
}
