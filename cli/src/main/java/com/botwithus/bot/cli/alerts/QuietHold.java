package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.cli.settings.QuietMode;
import com.botwithus.bot.cli.settings.Subscription;
import com.botwithus.bot.core.alerts.Alert;
import com.botwithus.bot.core.alerts.AlertKind;
import com.botwithus.bot.core.alerts.AlertMessage;
import com.botwithus.bot.core.alerts.AlertScheduler;
import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.alerts.QuietHours;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * What happens to an alert that quiet hours hold back: in {@link QuietMode#HOLD}
 * it is kept in {@link HeldAlerts} and, when quiet hours end, each service that is
 * switched on then gets one message listing what its column of the event grid
 * ticks <em>then</em>; in {@link QuietMode#MUTE} it is dropped.
 *
 * <p>What is held survives a host restart: {@link #resume()} sends it at once if
 * quiet hours have ended in the meantime, else when they end. Settings changes
 * while alerts are held apply at once: switching quiet hours off, or moving their
 * end to before now, sends what is held; switching to mute discards it; moving the
 * end moves the send.</p>
 *
 * <p>Thread-safe. Settings changes and the send at the end are handed to the
 * intake executor, like every alert, so none of this runs on the UI thread.</p>
 */
final class QuietHold {

    private static final Logger log = LoggerFactory.getLogger(QuietHold.class);

    private final AlertSettings settings;
    private final HeldAlerts held;
    private final AlertScheduler scheduler;
    private final Executor intake;
    private final Clock clock;
    private final ZoneId zone;
    private final BiConsumer<AlertService, AlertMessage> send;
    private final Object lock = new Object();
    /** Guarded by {@link #lock}; the send at the end of quiet hours, or {@code null} when none is due. */
    private AlertScheduler.Scheduled release;
    /** Guarded by {@link #lock}. */
    private boolean isClosed;

    /**
     * @param send sends one message to one service, as any alert is sent
     */
    QuietHold(AlertSettings settings, HeldAlerts held, AlertScheduler scheduler, Executor intake, Clock clock,
              ZoneId zone, BiConsumer<AlertService, AlertMessage> send) {
        this.settings = settings;
        this.held = held;
        this.scheduler = scheduler;
        this.intake = intake;
        this.clock = clock;
        this.zone = zone;
        this.send = send;
    }

    /** Holds {@code alert} for the end of quiet hours, or drops it when they mute. */
    void hold(Alert alert) {
        if (settings.quietMode() == QuietMode.MUTE) {
            log.debug("Quiet hours: muted a {} alert", alert.kind().id());
            return;
        }
        synchronized (lock) {
            held.add(alert);
            if (release == null) {
                reconsider();
            }
        }
    }

    /**
     * Deals with whatever a previous run held, then follows the quiet-hours
     * settings until the returned subscription is closed.
     */
    Subscription resume() {
        Subscription watch = settings.onQuietHoursChange(() -> onIntake(this::reconsider));
        reconsider();
        return watch;
    }

    /** Cancels the send at the end of quiet hours. What is held stays in the file for the next run. */
    void close() {
        synchronized (lock) {
            isClosed = true;
            cancelRelease();
        }
    }

    /** Sends, schedules or discards what is held, as the settings say now. */
    private void reconsider() {
        synchronized (lock) {
            cancelRelease();
            if (isClosed || held.isEmpty()) {
                return;
            }
            if (settings.quietMode() == QuietMode.MUTE) {
                HeldAlerts.Batch dropped = held.takeAll();
                log.info("Quiet hours now mute: discarded {} held alert(s)", dropped.total());
                return;
            }
            ZonedDateTime now = clock.instant().atZone(zone);
            Optional<QuietHours> quiet = settings.quietHours().filter(hours -> hours.contains(now.toLocalTime()));
            if (quiet.isPresent()) {
                release = scheduler.schedule(quiet.get().endAfter(now).toInstant(), () -> onIntake(this::reconsider));
            } else {
                deliver(held.takeAll());
            }
        }
    }

    private void deliver(HeldAlerts.Batch batch) {
        log.info("Quiet hours are over: sending {} held alert(s)", batch.total());
        for (AlertService service : AlertService.values()) {
            if (!settings.isEnabled(service)) {
                continue;
            }
            Predicate<AlertKind> routed = kind -> settings.isRouted(service, kind);
            List<Alert> kept = batch.keptWhere(routed);
            int overflow = batch.overflowWhere(routed);
            if (!kept.isEmpty() || overflow > 0) {
                send.accept(service, AlertMessage.ofHeld(kept, overflow, zone));
            }
        }
    }

    /** Call with {@link #lock} held. */
    private void cancelRelease() {
        if (release != null) {
            release.cancel();
            release = null;
        }
    }

    private void onIntake(Runnable task) {
        try {
            intake.execute(task);
        } catch (RejectedExecutionException e) {
            log.debug("Alerts are closed; held alerts wait for the next run");
        }
    }
}
