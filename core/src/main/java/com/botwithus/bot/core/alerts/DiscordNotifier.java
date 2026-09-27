package com.botwithus.bot.core.alerts;

import com.botwithus.bot.core.secrets.Secret;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Sends to a Discord channel through a webhook: a JSON {@code {"content": …}} with
 * the title in bold.
 *
 * <p>{@code allowed_mentions} is always set. It allows nothing, unless the user
 * turned on mentions and the message reports a problem; then the message starts
 * with {@code @here} and only that kind of mention is let through. Mentions inside
 * the message text — a script name that happens to contain {@code @everyone} — are
 * defused either way.</p>
 *
 * <p>A 429 is waited out for the {@code retry_after} Discord sends in the body
 * (falling back to the {@code Retry-After} header), within the
 * {@link RetryPolicy}'s limit.</p>
 */
public final class DiscordNotifier implements Notifier {

    /** Discord's limit on {@code content}, in characters. */
    public static final int MAX_CONTENT_CHARS = 2000;

    private static final String MENTION = "@here ";
    /** {@code allowed_mentions.parse} value that covers both {@code @here} and {@code @everyone}. */
    private static final String PARSE_EVERYONE = "everyone";
    private static final String ZERO_WIDTH_SPACE = "\u200B";
    private static final String ELLIPSIS = "…";
    private static final double MILLIS_PER_SECOND = Duration.ofSeconds(1).toMillis();

    private final URI webhook;
    private final boolean isMentioningProblems;
    private final HttpDelivery delivery;

    /**
     * @param webhook              the webhook URL
     * @param isMentioningProblems whether a problem message mentions {@code @here}
     * @throws IllegalArgumentException if the webhook is not an {@code https://} URL; the
     *                                  message never repeats the URL
     */
    public DiscordNotifier(Secret webhook, boolean isMentioningProblems, HttpDelivery delivery) {
        this.webhook = WebhookUrl.parseHttps(Objects.requireNonNull(webhook, "webhook"))
                .orElseThrow(() -> new IllegalArgumentException("The webhook URL must start with https://"));
        this.isMentioningProblems = isMentioningProblems;
        this.delivery = Objects.requireNonNull(delivery, "delivery");
    }

    @Override
    public AlertService service() {
        return AlertService.DISCORD;
    }

    @Override
    public SendResult send(AlertMessage message) {
        boolean isMentioning = isMentioningProblems && message.isProblem();
        String text = "**" + defuse(message.title()) + "**\n" + defuse(message.body());
        JsonObject json = new JsonObject();
        json.addProperty("content", fit((isMentioning ? MENTION : "") + text));
        json.add("allowed_mentions", allowedMentions(isMentioning));
        return delivery.post(new HttpPost(webhook, Map.of("Content-Type", SlackNotifier.JSON), json.toString()),
                DiscordNotifier::retryAfter);
    }

    private static JsonObject allowedMentions(boolean isMentioning) {
        JsonArray parse = new JsonArray();
        if (isMentioning) {
            parse.add(PARSE_EVERYONE);
        }
        JsonObject allowed = new JsonObject();
        allowed.add("parse", parse);
        return allowed;
    }

    /** Breaks {@code @everyone} and {@code @here} in untrusted text so they cannot ping. */
    private static String defuse(String text) {
        return text.replace("@everyone", "@" + ZERO_WIDTH_SPACE + "everyone")
                .replace("@here", "@" + ZERO_WIDTH_SPACE + "here");
    }

    private static String fit(String content) {
        if (content.length() <= MAX_CONTENT_CHARS) {
            return content;
        }
        int end = MAX_CONTENT_CHARS - ELLIPSIS.length();
        if (Character.isHighSurrogate(content.charAt(end - 1))) {
            end--;
        }
        return content.substring(0, end) + ELLIPSIS;
    }

    /** Discord's {@code retry_after} (seconds, fractional) from the body, else the header. */
    static Optional<Duration> retryAfter(HttpReply reply) {
        return bodyRetryAfter(reply.body()).or(() -> reply.header("Retry-After").flatMap(DiscordNotifier::seconds));
    }

    private static Optional<Duration> bodyRetryAfter(String body) {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                return Optional.empty();
            }
            JsonElement value = parsed.getAsJsonObject().get("retry_after");
            return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                    ? toDuration(value.getAsDouble()) : Optional.empty();
        } catch (JsonParseException e) {
            return Optional.empty();
        }
    }

    private static Optional<Duration> seconds(String text) {
        try {
            return toDuration(Double.parseDouble(text.strip()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<Duration> toDuration(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofMillis((long) Math.ceil(seconds * MILLIS_PER_SECOND)));
    }
}
