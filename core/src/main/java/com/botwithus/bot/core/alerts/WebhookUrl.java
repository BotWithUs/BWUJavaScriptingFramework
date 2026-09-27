package com.botwithus.bot.core.alerts;

import com.botwithus.bot.core.secrets.Secret;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * Checks that a stored webhook secret is an {@code https://} URL with a host.
 * Never echoes the URL back: a webhook URL is its own secret.
 */
public final class WebhookUrl {

    private static final String HTTPS = "https";
    private static final String HTTP = "http";

    private WebhookUrl() {
    }

    /** The webhook as a URI, if it is an {@code https://} URL with a host. */
    public static Optional<URI> parseHttps(Secret webhook) {
        return parse(webhook.reveal()).filter(uri -> HTTPS.equals(scheme(uri)));
    }

    /** {@code text} as a URI, if it is an {@code http://} or {@code https://} URL with a host. */
    public static Optional<URI> parseHttpOrHttps(String text) {
        return parse(text).filter(uri -> HTTPS.equals(scheme(uri)) || HTTP.equals(scheme(uri)));
    }

    private static Optional<URI> parse(String text) {
        try {
            URI uri = new URI(text.strip());
            return uri.getHost() == null ? Optional.empty() : Optional.of(uri);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    private static String scheme(URI uri) {
        return uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    }
}
