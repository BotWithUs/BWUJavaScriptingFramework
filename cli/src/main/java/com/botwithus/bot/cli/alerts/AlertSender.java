package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.core.alerts.AlertDelivery;
import com.botwithus.bot.core.alerts.AlertGateway;
import com.botwithus.bot.core.alerts.AlertKind;
import com.botwithus.bot.core.alerts.AlertResult;
import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.alerts.DeliverReply;
import com.botwithus.bot.core.alerts.DeliveryOutcome;
import com.botwithus.bot.core.alerts.SendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Hands finished alerts to the launcher, and records what became of them on the
 * {@link ServiceStatusBoard}. The host makes no request of its own: the launcher
 * renders and sends every message.
 *
 * <p>Every hand-over runs on the {@code sends} executor, one at a time and in
 * order, because it waits on the launcher's pipe. While no launcher that sends
 * alerts is connected (none yet, an older one, or one that stopped answering),
 * messages wait here, up to {@value #MAX_WAITING}, and go out in order, each with
 * its own dedupe key and time, when one is. The launcher drops a copy it already
 * has, and one that has waited too long; nothing here retries a message the
 * launcher answered.</p>
 */
public final class AlertSender implements AlertGateway.Listener {

    /** Messages kept while no launcher can take them; the oldest goes first when full. */
    static final int MAX_WAITING = 100;
    /** Delivery ids remembered so a result can name what it was about. */
    static final int MAX_REMEMBERED = 256;
    /** The status a send shows when the service is not set up on the launcher. */
    static final String NOT_SET_UP = "Not set up";
    static final String NOT_SENT = "Not sent";
    static final String QUEUE_FULL = "The launcher's queue for this service is full";
    static final String NOT_QUEUED = "The launcher did not queue it";
    static final String ADD_WEBHOOK = "Add the webhook URL first";
    static final String ADD_TOPIC = "Add the topic first";
    static final String SEVERAL_ALERTS = "Several alerts";
    static final String HELD_ALERTS = "Alerts held during quiet hours";

    private static final Logger log = LoggerFactory.getLogger(AlertSender.class);

    /** One message and what the Integrations card calls it. */
    private record Pending(AlertDelivery delivery, String subject) { }

    private final AlertGateway gateway;
    private final ServiceStatusBoard status;
    private final Executor sends;
    private final Clock clock;
    /** Only touched on the {@code sends} executor. */
    private final Deque<Pending> waiting = new ArrayDeque<>();
    /** Guarded by itself. */
    private final Map<Long, String> subjects = new LinkedHashMap<>();

    /**
     * @param sends runs each hand-over, in order; a single thread
     */
    public AlertSender(AlertGateway gateway, ServiceStatusBoard status, Executor sends, Clock clock) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.status = Objects.requireNonNull(status, "status");
        this.sends = Objects.requireNonNull(sends, "sends");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Queues {@code delivery} for the launcher. Never blocks. */
    public void send(AlertDelivery delivery, String subject) {
        Pending next = new Pending(Objects.requireNonNull(delivery, "delivery"),
                Objects.requireNonNull(subject, "subject"));
        onSends(() -> {
            waiting.addLast(next);
            trimWaiting();
            drain();
        });
    }

    /** How many messages wait for a launcher. For tests and the log. */
    int waitingCount() {
        return waiting.size();
    }

    @Override
    public void onAvailable() {
        onSends(this::drain);
    }

    @Override
    public void onUnavailable() {
        // Nothing to do: the next send finds the launcher gone and waits.
    }

    @Override
    public void onResult(AlertResult result) {
        if (result.isTest()) {
            return;
        }
        status.record(result.service(), LastSend.alert(result.toSendResult(), subjectOf(result)));
        if (!result.toSendResult().isDelivered()) {
            log.warn("Alert to {} was not delivered: {}", result.service().label(),
                    result.toSendResult().summary());
        }
    }

    /** Hands over what waits, oldest first, until it is gone or the launcher is. */
    private void drain() {
        while (!waiting.isEmpty()) {
            Pending next = waiting.peekFirst();
            switch (gateway.deliver(next.delivery())) {
                case DeliverReply.Answered answered -> {
                    waiting.removeFirst();
                    answered.outcomes().forEach(outcome -> record(outcome, next.subject()));
                }
                case DeliverReply.Rejected rejected -> {
                    waiting.removeFirst();
                    log.warn("The launcher refused a {} alert: {}", next.delivery().kind(), rejected.why());
                    next.delivery().services().forEach(service -> status.record(service,
                            LastSend.alert(failed(NOT_QUEUED), next.subject())));
                }
                case DeliverReply.Unavailable unavailable -> {
                    log.debug("Alerts wait for the launcher ({} waiting): {}", waiting.size(), unavailable.why());
                    return;
                }
            }
        }
    }

    private void record(DeliveryOutcome outcome, String subject) {
        if (outcome.isQueued()) {
            outcome.deliveryId().ifPresent(id -> remember(id, subject));
            return;
        }
        DeliveryOutcome.DropReason reason = outcome.dropReason().orElse(DeliveryOutcome.DropReason.UNKNOWN);
        if (reason == DeliveryOutcome.DropReason.DUPLICATE) {
            log.debug("The launcher already had the alert '{}' for {}", subject, outcome.service().label());
            return;
        }
        SendResult.Failed result = switch (reason) {
            case NOT_CONFIGURED -> new SendResult.Failed(clock.instant(), NOT_SET_UP,
                    notConfigured(outcome.service()), 0);
            case QUEUE_FULL -> failed(QUEUE_FULL);
            case UNKNOWN, DUPLICATE -> failed(NOT_QUEUED);
        };
        status.record(outcome.service(), LastSend.alert(result, subject));
        log.warn("Alert to {} was not queued: {}", outcome.service().label(), result.summary());
    }

    /** What the user has to fill in before {@code service} can be sent to; the launcher's own wording. */
    static String notConfigured(AlertService service) {
        return service == AlertService.NTFY ? ADD_TOPIC : ADD_WEBHOOK;
    }

    private SendResult.Failed failed(String reason) {
        return new SendResult.Failed(clock.instant(), NOT_SENT, reason, 0);
    }

    private void remember(long deliveryId, String subject) {
        synchronized (subjects) {
            subjects.put(deliveryId, subject);
            if (subjects.size() > MAX_REMEMBERED) {
                subjects.remove(subjects.keySet().iterator().next());
            }
        }
    }

    /** What the result was about: as the host worded it, or from its kind when this host did not send it. */
    private String subjectOf(AlertResult result) {
        synchronized (subjects) {
            String subject = subjects.remove(result.deliveryId());
            if (subject != null) {
                return subject;
            }
        }
        return describeKind(result.kind());
    }

    /** What a send of {@code kind} was about, for one this host has no subject for. */
    static String describeKind(String kind) {
        return switch (kind) {
            case AlertDelivery.KIND_BURST -> SEVERAL_ALERTS;
            case AlertDelivery.KIND_HELD -> HELD_ALERTS;
            default -> titleOf(kind);
        };
    }

    private static String titleOf(String kindId) {
        for (AlertKind kind : AlertKind.values()) {
            if (kind.id().equals(kindId)) {
                return kind.title();
            }
        }
        return kindId;
    }

    private void trimWaiting() {
        while (waiting.size() > MAX_WAITING) {
            Pending dropped = waiting.removeFirst();
            log.warn("No launcher has taken alerts for a while; dropped the oldest waiting ({})",
                    dropped.delivery().kind());
        }
    }

    private void onSends(Runnable task) {
        try {
            sends.execute(task);
        } catch (RejectedExecutionException e) {
            log.debug("Alerts are closed; a send was dropped");
        }
    }
}
