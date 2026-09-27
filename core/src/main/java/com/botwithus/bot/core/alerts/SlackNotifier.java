package com.botwithus.bot.core.alerts;

import com.botwithus.bot.core.secrets.Secret;
import com.google.gson.JsonObject;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

/**
 * Sends to a Slack channel through an incoming webhook: a JSON {@code {"text": …}}
 * with the title in bold. {@code &}, {@code <} and {@code >} are escaped as Slack
 * requires, so a script name cannot turn into a link or a mention.
 */
public final class SlackNotifier implements Notifier {

    static final String JSON = "application/json; charset=utf-8";

    private final URI webhook;
    private final HttpDelivery delivery;

    /**
     * @param webhook the incoming-webhook URL
     * @throws IllegalArgumentException if it is not an {@code https://} URL; the message
     *                                  never repeats the URL
     */
    public SlackNotifier(Secret webhook, HttpDelivery delivery) {
        this.webhook = WebhookUrl.parseHttps(Objects.requireNonNull(webhook, "webhook"))
                .orElseThrow(() -> new IllegalArgumentException("The webhook URL must start with https://"));
        this.delivery = Objects.requireNonNull(delivery, "delivery");
    }

    @Override
    public AlertService service() {
        return AlertService.SLACK;
    }

    @Override
    public SendResult send(AlertMessage message) {
        JsonObject json = new JsonObject();
        json.addProperty("text", "*" + escape(message.title()) + "*\n" + escape(message.body()));
        return delivery.post(new HttpPost(webhook, Map.of("Content-Type", JSON), json.toString()),
                RateLimitReader.NONE);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
