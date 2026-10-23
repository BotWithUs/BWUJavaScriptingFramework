package com.botwithus.bot.core.alerts;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.List;

/**
 * The service's own words for why it refused a request, cut to one short line.
 *
 * <p>Discord answers {@code {"message": "Unknown Webhook"}}, ntfy
 * {@code {"error": "unauthorized"}}, Slack a bare {@code no_service}. An HTML error
 * page says nothing useful in one line and is dropped. Any URL in the text is
 * {@link Redaction redacted}.</p>
 */
final class ServerReason {

    /** Longest reason kept, in characters. */
    static final int MAX_CHARS = 80;

    private static final List<String> MESSAGE_FIELDS = List.of("message", "error");
    private static final String ELLIPSIS = "…";

    private ServerReason() {
    }

    /** The reason in {@code body}, or empty if it has none worth showing. */
    static String of(String body) {
        String text = body.strip();
        if (text.isEmpty() || text.startsWith("<")) {
            return "";
        }
        String reason = text.startsWith("{") ? jsonMessage(text) : text.lines().findFirst().orElse("");
        return shorten(Redaction.scrub(reason.strip()));
    }

    private static String jsonMessage(String text) {
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                return "";
            }
            return firstStringField(parsed.getAsJsonObject());
        } catch (JsonParseException e) {
            return text.lines().findFirst().orElse("");
        }
    }

    private static String firstStringField(JsonObject object) {
        for (String field : MESSAGE_FIELDS) {
            JsonElement value = object.get(field);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                return value.getAsString();
            }
        }
        return "";
    }

    private static String shorten(String text) {
        return text.length() <= MAX_CHARS ? text : text.substring(0, MAX_CHARS - 1) + ELLIPSIS;
    }
}
