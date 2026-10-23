package com.botwithus.bot.core.alerts;

import com.botwithus.bot.core.secrets.Secret;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Sends to an ntfy topic: {@code POST <server>/<topic>} with the body as the
 * message, the title, priority and tag as headers, and the access token, when
 * there is one, as {@code Authorization: Bearer}.
 *
 * <p>A problem goes at {@code high} priority with the {@code warning} tag (a
 * warning sign on the phone); anything else at {@code default}. Header values are
 * sent as printable ASCII: a title character outside it becomes {@code ?}, and
 * the {@code ·} separator the host uses becomes {@code -}.</p>
 */
public final class NtfyNotifier implements Notifier {

    /** What ntfy accepts as a topic name. */
    public static final Pattern TOPIC = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private static final String PRIORITY_PROBLEM = "high";
    private static final String PRIORITY_NORMAL = "default";
    private static final String TAG_PROBLEM = "warning";
    private static final char FIRST_PRINTABLE = ' ';
    private static final char LAST_PRINTABLE = '~';
    private static final char MIDDLE_DOT = '·';

    private final URI topicUri;
    private final Optional<Secret> token;
    private final HttpDelivery delivery;

    /**
     * @param server the ntfy server, e.g. {@code https://ntfy.sh}
     * @param topic  the topic to publish to
     * @param token  the access token, if the topic needs one
     * @throws IllegalArgumentException if the server is not an http(s) URL or the topic is not valid
     */
    public NtfyNotifier(URI server, String topic, Optional<Secret> token, HttpDelivery delivery) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(topic, "topic");
        URI base = WebhookUrl.parseHttpOrHttps(server.toString())
                .orElseThrow(() -> new IllegalArgumentException("The server must be an http:// or https:// URL"));
        if (!TOPIC.matcher(topic).matches()) {
            throw new IllegalArgumentException("The topic must be 1 to 64 letters, digits, '_' or '-'");
        }
        this.topicUri = URI.create(stripTrailingSlashes(base.toString()) + "/" + topic);
        this.token = Objects.requireNonNull(token, "token");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
    }

    @Override
    public AlertService service() {
        return AlertService.NTFY;
    }

    @Override
    public SendResult send(AlertMessage message) {
        return delivery.post(new HttpPost(topicUri, headers(message), message.body()), RateLimitReader.NONE);
    }

    private Map<String, String> headers(AlertMessage message) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "text/plain; charset=utf-8");
        headers.put("Title", headerSafe(message.title()));
        headers.put("Priority", message.isProblem() ? PRIORITY_PROBLEM : PRIORITY_NORMAL);
        if (message.isProblem()) {
            headers.put("Tags", TAG_PROBLEM);
        }
        token.ifPresent(secret -> headers.put("Authorization", "Bearer " + secret.reveal()));
        return headers;
    }

    private static String headerSafe(String text) {
        StringBuilder safe = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> safe.append(asciiFor(codePoint)));
        return safe.toString();
    }

    private static char asciiFor(int codePoint) {
        if (codePoint == MIDDLE_DOT) {
            return '-';
        }
        boolean isPrintable = codePoint >= FIRST_PRINTABLE && codePoint <= LAST_PRINTABLE;
        return isPrintable ? (char) codePoint : '?';
    }

    private static String stripTrailingSlashes(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '/') {
            end--;
        }
        return text.substring(0, end);
    }
}
