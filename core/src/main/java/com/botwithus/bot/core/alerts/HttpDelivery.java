package com.botwithus.bot.core.alerts;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Posts a request with a bounded retry, and words the outcome as a {@link SendResult}.
 *
 * <ul>
 *   <li>2xx: delivered.</li>
 *   <li>5xx, a timeout, or a failed connection: tried again after a back-off, up to
 *       {@link RetryPolicy#maxAttempts()} attempts in all.</li>
 *   <li>429 with a wait the {@link RateLimitReader} reads, no longer than
 *       {@link RetryPolicy#longestRetryAfter()}: tried again after that wait, which
 *       counts as an attempt.</li>
 *   <li>Any other 4xx, a 3xx, or a TLS failure: failed at once; sending the same
 *       request again cannot help.</li>
 * </ul>
 *
 * <p>Blocks the calling thread for the whole exchange, waits included, so call it
 * from a thread that may block. Nothing it returns contains the request URL except
 * {@link Redaction redacted}, and nothing it returns quotes an exception message,
 * which could.</p>
 */
public final class HttpDelivery {

    private static final String TIMED_OUT = "Timed out";
    private static final String COULD_NOT_CONNECT = "Could not connect";
    private static final String TLS_FAILED = "Secure connection failed";
    private static final String NETWORK_ERROR = "Network error";
    private static final String CANCELLED = "Cancelled";
    private static final Duration ONE_SECOND = Duration.ofSeconds(1);

    /** What one attempt leads to. */
    private sealed interface Step {
        record Finished(SendResult result) implements Step { }
        record TryAgain(SendResult.Failed failure, Duration delay) implements Step { }
    }

    private final HttpTransport transport;
    private final RetryPolicy policy;
    private final Sleeper sleeper;
    private final Clock clock;

    public HttpDelivery(HttpTransport transport, RetryPolicy policy, Sleeper sleeper, Clock clock) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Posts {@code request}, retrying as described above. */
    public SendResult post(HttpPost request, RateLimitReader rateLimit) {
        Instant started = clock.instant();
        int attempt = 1;
        while (true) {
            switch (attempt(request, rateLimit, attempt, started)) {
                case Step.Finished finished -> {
                    return finished.result();
                }
                case Step.TryAgain again -> {
                    if (attempt >= policy.maxAttempts()) {
                        return again.failure();
                    }
                    if (!pause(again.delay())) {
                        return failed(CANCELLED, "", attempt);
                    }
                    attempt++;
                }
            }
        }
    }

    private Step attempt(HttpPost request, RateLimitReader rateLimit, int attempt, Instant started) {
        String where = Redaction.url(request.uri());
        try {
            return judge(transport.post(request), rateLimit, attempt, started);
        } catch (HttpTimeoutException e) {
            return new Step.TryAgain(failed(TIMED_OUT, where, attempt), policy.backoffAfter(attempt));
        } catch (ConnectException e) {
            return new Step.TryAgain(failed(COULD_NOT_CONNECT, where, attempt), policy.backoffAfter(attempt));
        } catch (SSLException e) {
            return new Step.Finished(failed(TLS_FAILED, where, attempt));
        } catch (IOException e) {
            return new Step.TryAgain(failed(NETWORK_ERROR, where, attempt), policy.backoffAfter(attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Step.Finished(failed(CANCELLED, "", attempt));
        }
    }

    private Step judge(HttpReply reply, RateLimitReader rateLimit, int attempt, Instant started) {
        Instant now = clock.instant();
        if (reply.isSuccess()) {
            return new Step.Finished(new SendResult.Delivered(now, Duration.between(started, now), attempt));
        }
        SendResult.Failed failure = new SendResult.Failed(now, HttpStatusText.of(reply.status()),
                ServerReason.of(reply.body()), attempt);
        if (reply.isRateLimited()) {
            return rateLimited(rateLimit.retryAfter(reply), failure);
        }
        if (reply.isServerError()) {
            return new Step.TryAgain(failure, policy.backoffAfter(attempt));
        }
        return new Step.Finished(failure);
    }

    private Step rateLimited(Optional<Duration> wait, SendResult.Failed failure) {
        if (wait.isEmpty()) {
            return new Step.Finished(failure);
        }
        if (wait.get().compareTo(policy.longestRetryAfter()) > 0) {
            long seconds = (long) Math.ceil(wait.get().toMillis() / (double) ONE_SECOND.toMillis());
            return new Step.Finished(new SendResult.Failed(failure.at(), failure.status(),
                    "Asked to wait " + seconds + " s", failure.attempts()));
        }
        return new Step.TryAgain(failure, wait.get());
    }

    private boolean pause(Duration wait) {
        try {
            sleeper.sleep(wait);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private SendResult.Failed failed(String status, String reason, int attempt) {
        return new SendResult.Failed(clock.instant(), status, reason, attempt);
    }
}
