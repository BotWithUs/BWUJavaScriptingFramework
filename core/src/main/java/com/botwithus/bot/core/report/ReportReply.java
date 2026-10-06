package com.botwithus.bot.core.report;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * How a report ended. The user sees {@link #userMessage()} exactly as written,
 * whichever way it went: the launcher words its own outcomes, and the host
 * words only the few it detects itself ({@link Failed#launcherNotRunning()},
 * {@link Failed#launcherTimeout()}, {@link Failed#badReply()}).
 */
public sealed interface ReportReply {

    /** What an error reply with no {@code error} field is logged as; its message is still shown. */
    String UNSPECIFIED_ERROR = "unspecified";

    /** The text to show the user, unchanged. */
    String userMessage();

    /**
     * The report reached the script's author.
     *
     * @param code        the short code the user can quote, e.g. {@code BWU-7K3Q9P}
     * @param ticketUrl   where the ticket lives; empty when the launcher gave none
     * @param userMessage what to show
     */
    record Sent(String code, Optional<String> ticketUrl, String userMessage) implements ReportReply {
        public Sent {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(ticketUrl, "ticketUrl");
            Objects.requireNonNull(userMessage, "userMessage");
        }
    }

    /**
     * The report did not go.
     *
     * @param error        the machine name of what went wrong; for logs, never shown
     * @param retryAfterS  seconds to wait, when the website rate-limited the user
     * @param userMessage  what to show
     */
    record Failed(String error, OptionalLong retryAfterS, String userMessage) implements ReportReply {

        /** No launcher claimed the request in time. */
        public static final String LAUNCHER_NOT_RUNNING = "launcher_not_running";
        /** A launcher claimed it but never answered. */
        public static final String LAUNCHER_TIMEOUT = "launcher_timeout";
        /** The answer could not be read. */
        public static final String BAD_REPLY = "bad_reply";

        public Failed {
            Objects.requireNonNull(error, "error");
            Objects.requireNonNull(retryAfterS, "retryAfterS");
            Objects.requireNonNull(userMessage, "userMessage");
        }

        public static Failed launcherNotRunning() {
            return new Failed(LAUNCHER_NOT_RUNNING, OptionalLong.empty(),
                    "The BotWithUs launcher isn't running. Open it, then try again.");
        }

        public static Failed launcherTimeout() {
            return new Failed(LAUNCHER_TIMEOUT, OptionalLong.empty(),
                    "The launcher took too long to send your report. Try again in a few minutes.");
        }

        public static Failed badReply() {
            return new Failed(BAD_REPLY, OptionalLong.empty(),
                    "Something went wrong sending your report. Try again in a few minutes.");
        }
    }

    /**
     * Reads the launcher's {@code .rpt}. Only {@code status} decides the outcome;
     * anything that is not a JSON object with a {@code status} and a
     * {@code user_message} is {@link Failed#badReply()}, as is an {@code ok}
     * without a code.
     */
    static ReportReply parse(String json) {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return Failed.badReply();
            }
            return fromObject(root.getAsJsonObject());
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException
                 | NumberFormatException e) {
            return Failed.badReply();
        }
    }

    private static ReportReply fromObject(JsonObject o) {
        Optional<String> status = string(o, "status");
        Optional<String> message = string(o, "user_message");
        if (status.isEmpty() || message.isEmpty()) {
            return Failed.badReply();
        }
        return switch (status.get()) {
            case "ok" -> string(o, "code")
                    .<ReportReply>map(code -> new Sent(code, string(o, "ticket_url"), message.get()))
                    .orElseGet(Failed::badReply);
            case "error" -> new Failed(string(o, "error").orElse(UNSPECIFIED_ERROR), retryAfter(o),
                    message.get());
            default -> Failed.badReply();
        };
    }

    private static OptionalLong retryAfter(JsonObject o) {
        JsonElement e = o.get("retry_after");
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()
                ? OptionalLong.of(e.getAsLong()) : OptionalLong.empty();
    }

    private static Optional<String> string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()
                ? Optional.of(e.getAsString()) : Optional.empty();
    }
}
