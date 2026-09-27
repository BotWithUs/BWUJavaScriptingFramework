package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.cli.events.HostEvent;
import com.botwithus.bot.core.alerts.Alert;
import com.botwithus.bot.core.alerts.AlertKind;
import com.botwithus.bot.core.alerts.AlertMessage;
import com.botwithus.bot.core.alerts.AlertScheduler;
import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.alerts.Burst;
import com.botwithus.bot.core.alerts.BurstGrouper;
import com.botwithus.bot.core.alerts.QuietHours;
import com.botwithus.bot.core.alerts.SendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Sends host events to ntfy, Slack and Discord as alerts.
 *
 * <p>Subscribe it to the host event bus. {@link #accept} only hands the event to
 * the intake executor, so the bus thread never classifies, reads a setting or
 * touches the network. On the intake thread each event is
 * {@link AlertClassifier classified}, dropped if quiet hours hold it back, and
 * routed to every service that is switched on and ticked for its kind in the
 * event grid. Per service, alerts within the burst window are collected into one
 * message (a daily summary never waits); each send then runs on that service's
 * {@link DeliveryLanes lane}, and its result is recorded on the
 * {@link ServiceStatusBoard}.</p>
 *
 * <p>Every setting is read when the alert arrives, so changes apply to the next one.
 * Nothing is sent, and no request is made, until the user switches a service on.</p>
 */
public final class AlertDispatcher implements Consumer<HostEvent> {

    private static final Logger log = LoggerFactory.getLogger(AlertDispatcher.class);

    private final AlertSettings settings;
    private final AlertClassifier classifier;
    private final NotifierSource notifiers;
    private final ServiceStatusBoard status;
    private final AlertScheduler scheduler;
    private final Executor intake;
    private final DeliveryLanes lanes;
    private final Clock clock;
    private final ZoneId zone;
    private final Map<AlertService, BurstGrouper> bursts = new EnumMap<>(AlertService.class);

    /**
     * @param intake runs the classification and routing of each event, off the bus thread
     * @param lanes  runs each send, one lane per service
     * @param zone   the zone quiet hours and message times are in
     */
    public AlertDispatcher(AlertSettings settings, AlertClassifier classifier, NotifierSource notifiers,
                           ServiceStatusBoard status, AlertScheduler scheduler, Executor intake,
                           DeliveryLanes lanes, Clock clock, ZoneId zone) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.notifiers = Objects.requireNonNull(notifiers, "notifiers");
        this.status = Objects.requireNonNull(status, "status");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.intake = Objects.requireNonNull(intake, "intake");
        this.lanes = Objects.requireNonNull(lanes, "lanes");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zone = Objects.requireNonNull(zone, "zone");
        for (AlertService service : AlertService.values()) {
            bursts.put(service, new BurstGrouper());
        }
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
            log.debug("Quiet hours: held back a {} alert", alert.kind().id());
            return;
        }
        Duration window = settings.burstWindow();
        for (AlertService service : settings.targets(alert.kind())) {
            if (window.isZero() || isScheduled(alert.kind())) {
                send(service, AlertMessage.of(alert), alert.headline());
            } else {
                bursts.get(service).add(alert, now, window)
                        .ifPresent(closesAt -> scheduler.schedule(closesAt, () -> flush(service)));
            }
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
        bursts.get(service).takeDue(clock.instant()).ifPresent(burst ->
                send(service, AlertMessage.of(burst, zone), subjectOf(burst)));
    }

    private static String subjectOf(Burst burst) {
        return burst.total() == 1 ? burst.alerts().getFirst().headline() : burst.total() + " alerts";
    }

    private void send(AlertService service, AlertMessage message, String subject) {
        lanes.submit(service, () -> {
            SendResult result = switch (notifiers.forService(service)) {
                case NotifierSetup.Ready ready -> ready.notifier().send(message);
                case NotifierSetup.NotReady notReady -> notReady.asResult(clock.instant());
            };
            status.record(service, LastSend.alert(result, subject));
            if (!result.isDelivered()) {
                log.warn("Alert to {} was not delivered: {}", service.label(), result.summary());
            }
        });
    }
}
