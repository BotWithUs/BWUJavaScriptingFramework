package com.botwithus.bot.core.alerts;

import java.util.Map;

/**
 * {@code 401 Unauthorized}-style text for a status code. The JDK client does not
 * expose the server's reason phrase (HTTP/2 has none), so the standard phrases
 * for the codes a webhook plausibly returns are kept here.
 */
final class HttpStatusText {

    private static final Map<Integer, String> PHRASES = Map.ofEntries(
            Map.entry(301, "Moved Permanently"),
            Map.entry(302, "Found"),
            Map.entry(307, "Temporary Redirect"),
            Map.entry(308, "Permanent Redirect"),
            Map.entry(400, "Bad Request"),
            Map.entry(401, "Unauthorized"),
            Map.entry(403, "Forbidden"),
            Map.entry(404, "Not Found"),
            Map.entry(405, "Method Not Allowed"),
            Map.entry(410, "Gone"),
            Map.entry(413, "Content Too Large"),
            Map.entry(415, "Unsupported Media Type"),
            Map.entry(429, "Too Many Requests"),
            Map.entry(500, "Internal Server Error"),
            Map.entry(502, "Bad Gateway"),
            Map.entry(503, "Service Unavailable"),
            Map.entry(504, "Gateway Timeout"));

    private HttpStatusText() {
    }

    /** {@code code} followed by its phrase when it has a known one, e.g. {@code 404 Not Found}. */
    static String of(int code) {
        String phrase = PHRASES.get(code);
        return phrase == null ? Integer.toString(code) : code + " " + phrase;
    }
}
