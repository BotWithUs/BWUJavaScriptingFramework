package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.core.alerts.AlertScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Emits the daily summary once a day at the configured local time.
 *
 * <p>Only one summary is ever pending. Changing the time ({@link #reschedule()})
 * replaces it; a summary that throws is logged and tomorrow's is still scheduled.
 * On a day the clocks change, a time that does not exist (02:30 on the spring
 * change) runs at the equivalent moment after the gap.</p>
 */
public final class DailySummaryJob implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DailySummaryJob.class);

    private final AlertScheduler scheduler;
    private final Clock clock;
    private final ZoneId zone;
    private final Supplier<LocalTime> summaryAt;
    private final Runnable emit;
    private final Object lock = new Object();
    /** Guarded by {@link #lock}. */
    private AlertScheduler.Scheduled pending;
    /** Guarded by {@link #lock}. */
    private boolean isClosed;

    /**
     * @param summaryAt read each time the next summary is scheduled
     * @param emit      composes and sends the summary; runs on the scheduler's thread
     */
    public DailySummaryJob(AlertScheduler scheduler, Clock clock, ZoneId zone,
                           Supplier<LocalTime> summaryAt, Runnable emit) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.summaryAt = Objects.requireNonNull(summaryAt, "summaryAt");
        this.emit = Objects.requireNonNull(emit, "emit");
    }

    /** Schedules the next summary. */
    public void start() {
        reschedule();
    }

    /** Drops the pending summary and schedules it again for the time set now. */
    public void reschedule() {
        synchronized (lock) {
            if (isClosed) {
                return;
            }
            if (pending != null) {
                pending.cancel();
            }
            pending = scheduler.schedule(nextRun(clock.instant(), zone, summaryAt.get()), this::fire);
        }
    }

    /** Drops the pending summary; nothing is scheduled after this. */
    @Override
    public void close() {
        synchronized (lock) {
            isClosed = true;
            if (pending != null) {
                pending.cancel();
                pending = null;
            }
        }
    }

    /** The first moment strictly after {@code now} whose local time in {@code zone} is {@code at}. */
    public static Instant nextRun(Instant now, ZoneId zone, LocalTime at) {
        LocalDate today = now.atZone(zone).toLocalDate();
        Instant candidate = ZonedDateTime.of(today, at, zone).toInstant();
        if (candidate.isAfter(now)) {
            return candidate;
        }
        return ZonedDateTime.of(today.plusDays(1), at, zone).toInstant();
    }

    private void fire() {
        try {
            emit.run();
        } catch (RuntimeException e) {
            log.warn("Daily summary failed: {}", e.toString());
        }
        reschedule();
    }
}
