package com.botwithus.bot.core.alerts;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * {@link HttpTransport} over the JDK's {@link HttpClient}.
 *
 * <p>Both connecting and the whole exchange are bounded by one timeout. Redirects
 * are not followed: a webhook that answers with a redirect is misconfigured, and
 * following it would post the message somewhere the user did not choose. Only the
 * first {@link #MAX_BODY_BYTES} of a reply are read, which is plenty for the error
 * text a service returns.</p>
 */
public final class JdkHttpTransport implements HttpTransport, AutoCloseable {

    /** The timeout the host uses for every alert request. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /** How much of a reply body is kept. */
    static final int MAX_BODY_BYTES = 4096;

    private final HttpClient client;
    private final Duration timeout;

    /** A transport with {@link #DEFAULT_TIMEOUT}. */
    public JdkHttpTransport() {
        this(DEFAULT_TIMEOUT);
    }

    /** A transport that gives up on a request after {@code timeout}. */
    public JdkHttpTransport(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public HttpReply post(HttpPost request) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8));
        request.headers().forEach(builder::header);
        HttpResponse<InputStream> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            String text = new String(body.readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8);
            return new HttpReply(response.statusCode(), firstValues(response.headers()), text);
        }
    }

    /** Stops taking requests. Does not wait for one in flight, so shutdown never hangs on a slow service. */
    @Override
    public void close() {
        client.shutdown();
    }

    private static Map<String, String> firstValues(HttpHeaders headers) {
        Map<String, String> first = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : headers.map().entrySet()) {
            if (!entry.getValue().isEmpty()) {
                first.putIfAbsent(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue().getFirst());
            }
        }
        return first;
    }
}
