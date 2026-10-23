package com.botwithus.bot.core.alerts;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What the server answered.
 *
 * @param status  the HTTP status code
 * @param headers response headers, first value of each, keyed in lower case
 * @param body    the start of the body as text; possibly empty
 */
public record HttpReply(int status, Map<String, String> headers, String body) {

    private static final int FIRST_SUCCESS = 200;
    private static final int FIRST_REDIRECT = 300;
    private static final int FIRST_CLIENT_ERROR = 400;
    private static final int FIRST_SERVER_ERROR = 500;
    private static final int TOO_MANY_REQUESTS = 429;

    public HttpReply {
        headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
        Objects.requireNonNull(body, "body");
    }

    /** A reply with no headers. */
    public HttpReply(int status, String body) {
        this(status, Map.of(), body);
    }

    /** The first value of header {@code name}, matched ignoring case. */
    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
    }

    /** 2xx. */
    public boolean isSuccess() {
        return status >= FIRST_SUCCESS && status < FIRST_REDIRECT;
    }

    /** 4xx: the request was wrong, and sending it again will not help. */
    public boolean isClientError() {
        return status >= FIRST_CLIENT_ERROR && status < FIRST_SERVER_ERROR;
    }

    /** 5xx: the server failed, and a later try may work. */
    public boolean isServerError() {
        return status >= FIRST_SERVER_ERROR;
    }

    /** 429: the service is rate-limiting this sender. */
    public boolean isRateLimited() {
        return status == TOO_MANY_REQUESTS;
    }
}
