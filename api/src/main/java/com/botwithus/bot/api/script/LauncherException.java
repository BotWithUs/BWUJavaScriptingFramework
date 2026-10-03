package com.botwithus.bot.api.script;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * A {@link ClientLauncher} call that failed. {@link #code()} is a stable
 * snake_case identifier and is what to branch on; the message is English for
 * logs and is never parsed.
 *
 * <p>Most codes come from the launcher service. {@link #SERVICE_UNAVAILABLE} and
 * {@link #SERVICE_STOPPED} are raised by the host when no service answered.</p>
 */
public final class LauncherException extends RuntimeException {

    /** No service is reachable, or it did not answer in time. Retry later. */
    public static final String SERVICE_UNAVAILABLE = "service_unavailable";
    /** The user stopped the service from the tray. The host will not start it. */
    public static final String SERVICE_STOPPED = "service_stopped";
    /** The call is outside this script's targets, or not allowed on this surface. */
    public static final String NOT_PERMITTED = "not_permitted";
    /** The automation launch budget is spent; see {@link #retryAfterMs()}. */
    public static final String RATE_LIMITED = "rate_limited";
    /** No account has that id. */
    public static final String ACCOUNT_NOT_FOUND = "account_not_found";
    /** No client has that id. */
    public static final String CLIENT_NOT_FOUND = "client_not_found";

    private static final long serialVersionUID = 1L;

    private final String code;
    private final boolean isRetryable;
    private final long retryAfterMs;
    private final String field;

    /**
     * @param code    the wire or host code
     * @param message English text for logs
     */
    public LauncherException(String code, String message) {
        this(code, message, false, OptionalLong.empty(), Optional.empty());
    }

    /**
     * @param code         the wire or host code
     * @param message      English text for logs
     * @param isRetryable  whether the service said a retry may succeed
     * @param retryAfterMs for {@code rate_limited}, how long to wait
     * @param field        for {@code bad_request}, the offending field
     */
    public LauncherException(String code, String message, boolean isRetryable,
                             OptionalLong retryAfterMs, Optional<String> field) {
        super(Objects.requireNonNull(code, "code") + ": " + message);
        this.code = code;
        this.isRetryable = isRetryable;
        this.retryAfterMs = retryAfterMs.orElse(-1L);
        this.field = field.orElse(null);
    }

    /**
     * @param code    the host code
     * @param message English text for logs
     * @param cause   what failed underneath
     */
    public LauncherException(String code, String message, Throwable cause) {
        this(code, message);
        initCause(cause);
    }

    /** @return the stable code, for example {@code "rate_limited"} */
    public String code() {
        return code;
    }

    /** @return whether the service marked the failure retryable */
    public boolean isRetryable() {
        return isRetryable;
    }

    /** @return for {@code rate_limited}, the wait before a launch can succeed */
    public OptionalLong retryAfterMs() {
        return retryAfterMs < 0 ? OptionalLong.empty() : OptionalLong.of(retryAfterMs);
    }

    /** @return for {@code bad_request}, the field the service refused */
    public Optional<String> field() {
        return Optional.ofNullable(field);
    }
}
