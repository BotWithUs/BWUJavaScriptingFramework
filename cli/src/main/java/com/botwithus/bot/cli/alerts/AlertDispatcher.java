package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.cli.events.HostEvent;
import com.botwithus.bot.cli.settings.Subscription;
import com.botwithus.bot.core.alerts.Alert;
import com.botwithus.bot.core.alerts.AlertDelivery;
import com.botwithus.bot.core.alerts.AlertKind;
import com.botwithus.bot.core.alerts.AlertMessage;
import com.botwithus.bot.core.alerts.AlertScheduler;
import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.alerts.Burst;
import com.botwithus.bot.core.alerts.BurstGrouper;
import com.botwithus.bot.core.alerts.QuietHours;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Decides which host events become alerts, when, and for which of ntfy, Slack and
 * Discord; the launcher sends them.
 *
 * <p>Subscribe it to the host event bus. {@link #accept} only hands the event to
 * the intake executor, so the bus thread never classifies, reads a setting or
 * touches the launcher's pipe. On the intake thread each event is
 * {@link AlertClassifier classified}, handed to {@link QuietHold} if quiet hours
 * hold it back (held for when they end, or muted), and otherwise
 * routed to every service that is switched on and ticked for its kind in the
 * event grid. Per service, alerts within the burst window are collected into one
 * message (a daily summary never waits). Each finished message goes to the
 * {@link AlertSender}, which hands it to the launcher.</p>
 *
 * <p>Every setting is read when the alert arrives, so changes apply to the next one.
 * Nothing is handed over until the user switches a service on. Call
 * {@link #resumeHeld()} once when the host starts, and {@link #close()} when it
 * stops.</p>
 */
public final class AlertDispatcher implements Consumer<HostEvent>, AutoCloseable {

    private final AlertSettings settings;
    private final AlertClassifier classifier;
    private final AlertSender sender;
    private final AlertScheduler scheduler;
    private final Executor intake;
    private final QuietHold quietHold;
    private final Clock clock;
    private final ZoneId zone;
    private final Map<AlertService, BurstGrouper> bursts = new EnumMap<>(AlertService.class);

    /**
     * @param sender hands each finished message to the launcher
     * @param intake runs the classification and routing of each event, off the bus thread
     * @param held   what quiet hours are holding back, kept across restarts
     * @param zone   the zone quiet hours and message times are in
     */
    public AlertDispatcher(AlertSettings settings, AlertClassifier classifier, AlertSender sender,
                           AlertScheduler scheduler, Executor intake, HeldAlerts held, Clock clock, ZoneId zone) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.intake = Objects.requireNonNull(intake, "intake");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.quietHold = new QuietHold(settings, Objects.requireNonNull(held, "held"), scheduler, intake, clock,
                zone, (service, message) -> sender.send(AlertDelivery.compose(AlertDelivery.KIND_HELD, message,
                        Set.of(service), clock.instant()), message.title()));
        for (AlertService service : AlertService.values()) {
            bursts.put(service, new BurstGrouper());
        }
    }

    /**
     * Sends or schedules what quiet hours held back before the host last stopped,
     * and from now on applies quiet-hours setting changes to what is held. Call once,
     * when the host starts; closing the subscription stops following the settings.
     */
    public Subscription resumeHeld() {
        return quietHold.resume();
    }

    /** Cancels the pending send of held alerts. They stay saved, for the next start. */
    @Override
    public void close() {
        quietHold.close();
    }

    /** Hands {@code event} to the intake executor; never blocks the publishing thread. */
    @Override
    public void accept(HostEvent event) {
        intake.execute(() -> classifier.classify(event).ifPresent(this::dispatch));
    }

    /** Routes {@code alert} to every service that should get it, now or in a burst. */
    public void dispatch(Alert alert) {
        Instant now = clock.instant();
        LocalTime localTime = now.atZone(zone).toLocalTime();
        Optional<QuietHours> quiet = settings.quietHours();
        if (quiet.isPresent() && !quiet.get().lets(alert.kind(), localTime) && !isScheduled(alert.kind())) {
            quietHold.hold(alert);
            return;
        }
        Set<AlertService> targets = settings.targets(alert.kind());
        if (targets.isEmpty()) {
            return;
        }
        Duration window = settings.burstWindow();
        if (window.isZero() || isScheduled(alert.kind())) {
            sender.send(AlertDelivery.compose(alert.kind().id(), AlertMessage.of(alert), targets, alert.at()),
                    alert.headline());
            return;
        }
        for (AlertService service : targets) {
            bursts.get(service).add(alert, now, window)
                    .ifPresent(closesAt -> scheduler.schedule(closesAt, () -> flush(service)));
        }
    }

    /**
     * The daily summary goes out at the time the user chose, whether or not it falls
     * in quiet hours, and is never held in a burst.
     */
    private static boolean isScheduled(AlertKind kind) {
        return kind == AlertKind.DAILY_SUMMARY;
    }

    private void flush(AlertService service) {
        bursts.get(service).takeDue(clock.instant())
                .ifPresent(burst -> sender.send(deliveryOf(burst, service), subjectOf(burst)));
    }

    /** A burst of one goes out as that alert would have; a bigger one as a burst, timed when it closed. */
    private AlertDelivery deliveryOf(Burst burst, AlertService service) {
        if (burst.total() == 1) {
            Alert only = burst.alerts().getFirst();
            return AlertDelivery.compose(only.kind().id(), AlertMessage.of(only), Set.of(service), only.at());
        }
        return AlertDelivery.compose(AlertDelivery.KIND_BURST, AlertMessage.of(burst, zone), Set.of(service),
                clock.instant());
    }

    private static String subjectOf(Burst burst) {
        return burst.total() == 1 ? burst.alerts().getFirst().headline() : burst.total() + " alerts";
    }
}
