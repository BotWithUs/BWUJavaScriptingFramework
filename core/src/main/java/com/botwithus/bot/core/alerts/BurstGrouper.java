package com.botwithus.bot.core.alerts;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Collects alerts that arrive close together so they go out as one message.
 *
 * <p>The first alert opens a burst that closes a fixed window later; every alert
 * that arrives before then joins it. The window does not slide, so no alert waits
 * longer than the window. The caller owns time: {@link #add} returns the moment to
 * come back, and {@link #takeDue} hands over the burst once that moment has come.
 * Thread-safe.</p>
 */
public final class BurstGrouper {

    /** Alerts kept per burst; later ones are only counted. */
    public static final int MAX_PENDING = 200;

    private final Object lock = new Object();
    /** Guarded by {@link #lock}. */
    private final List<Alert> pending = new ArrayList<>();
    /** Guarded by {@link #lock}; {@code null} while idle. */
    private Instant closesAt;
    /** Guarded by {@link #lock}. */
    private int overflow;

    /** An idle grouper. */
    public BurstGrouper() {
    }

    /**
     * Adds {@code alert} to the open burst, opening one if there is none.
     *
     * @param window how long a new burst stays open; must be positive
     * @return when the burst closes, if this call opened it; empty if it joined one
     */
    public Optional<Instant> add(Alert alert, Instant now, Duration window) {
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window must be positive: " + window);
        }
        synchronized (lock) {
            if (pending.size() < MAX_PENDING) {
                pending.add(alert);
            } else {
                overflow++;
            }
            if (closesAt != null) {
                return Optional.empty();
            }
            closesAt = now.plus(window);
            return Optional.of(closesAt);
        }
    }

    /** The burst, if it has closed by {@code now}; the grouper is idle again after. */
    public Optional<Burst> takeDue(Instant now) {
        synchronized (lock) {
            if (closesAt == null || now.isBefore(closesAt)) {
                return Optional.empty();
            }
            Burst burst = new Burst(pending, overflow);
            pending.clear();
            overflow = 0;
            closesAt = null;
            return Optional.of(burst);
        }
    }
}
