package com.botwithus.bot.core.alerts;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

/**
 * One HTTP POST a notifier wants made.
 *
 * <p>{@link #toString()} redacts the URL and leaves out the headers and body, so
 * the request can be logged without leaking a webhook or a token.</p>
 *
 * @param uri     where to post; for a webhook this is the secret itself
 * @param headers request headers
 * @param body    the body, sent as UTF-8
 */
public record HttpPost(URI uri, Map<String, String> headers, String body) {

    public HttpPost {
        Objects.requireNonNull(uri, "uri");
        headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
        Objects.requireNonNull(body, "body");
    }

    @Override
    public String toString() {
        return "HttpPost[" + Redaction.url(uri) + "]";
    }
}
