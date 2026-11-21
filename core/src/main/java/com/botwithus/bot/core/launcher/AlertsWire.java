package com.botwithus.bot.core.launcher;

import com.botwithus.bot.core.alerts.AlertDelivery;
import com.botwithus.bot.core.alerts.AlertResult;
import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.alerts.DeliveryOutcome;
import com.botwithus.bot.core.alerts.IntegrationChange;
import com.botwithus.bot.core.alerts.IntegrationState;
import com.botwithus.bot.core.alerts.ServiceState;
import com.botwithus.msgpack.MsgMap;
import com.botwithus.msgpack.MsgValue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The {@code alerts.*} bodies, both ways, in canonical form (ADR 2.5): request keys
 * in the order of the wire shape's tables, an absent optional key omitted. A
 * response or event field the service did not send reads as its default, and an
 * entry naming a service this host does not know is skipped.
 */
final class AlertsWire {

    /** Longest {@code title} the service accepts, in characters. */
    static final int MAX_TITLE_CHARS = 256;
    /** Largest {@code body} the service accepts, in UTF-8 bytes. */
    static final int MAX_BODY_BYTES = 8000;
    /** The service's order, which it also lists services in. */
    static final List<AlertService> WIRE_ORDER = List.of(AlertService.DISCORD, AlertService.SLACK,
            AlertService.NTFY);

    static final String SEVERITY_PROBLEM = "problem";
    static final String SEVERITY_INFO = "info";
    static final String OUTCOME_QUEUED = "queued";

    private AlertsWire() {
    }

    /**
     * @return {@code alerts.deliver}'s body; the title and body cut to the service's
     *         limits. This host never sends {@code fields}.
     */
    static MsgValue deliver(AlertDelivery delivery) {
        List<MsgValue> services = WIRE_ORDER.stream().filter(delivery.services()::contains)
                .map(service -> MsgValue.of(service.id())).toList();
        var builder = MsgMap.builder()
                .put(key("kind"), MsgValue.of(delivery.kind()))
                .put(key("severity"), MsgValue.of(delivery.message().isProblem() ? SEVERITY_PROBLEM : SEVERITY_INFO))
                .put(key("title"), MsgValue.of(capCodePoints(delivery.message().title(), MAX_TITLE_CHARS)))
                .put(key("body"), MsgValue.of(capUtf8(delivery.message().body(), MAX_BODY_BYTES)))
                .put(key("services"), MsgValue.of(services));
        delivery.dedupeKey().ifPresent(dedupeKey -> builder.put(key("dedupeKey"), MsgValue.of(dedupeKey)));
        return builder.put(key("ts"), MsgValue.of(delivery.at().toEpochMilli())).build();
    }

    /** @return the body of {@code alerts.integrations.get} and {@code alerts.status}: empty */
    static MsgValue emptyRequest() {
        return MsgMap.empty();
    }

    /** @return what {@code alerts.deliver} did for each service */
    static List<DeliveryOutcome> deliverOutcomes(MsgValue body) {
        return WireValues.array(body, "results").stream().filter(WireValues::isMap)
                .flatMap(r -> service(r).map(s -> outcome(s, r)).stream()).toList();
    }

    /** @return {@code alerts.integrations.set}'s body: only what changes */
    static MsgValue change(IntegrationChange change) {
        var builder = MsgMap.builder().put(key("service"), MsgValue.of(change.service().id()));
        if (change.isClearingSecret()) {
            builder.put(key("secret"), MsgValue.of(""));
        }
        change.secret().ifPresent(secret -> builder.put(key("secret"), MsgValue.of(secret.reveal())));
        change.isMentioningHere().ifPresent(isOn -> builder.put(key("mentionHere"), MsgValue.of(isOn)));
        change.server().ifPresent(server -> builder.put(key("server"), MsgValue.of(server)));
        change.topic().ifPresent(topic -> builder.put(key("topic"), MsgValue.of(topic)));
        return builder.build();
    }

    /** @return the services in {@code alerts.integrations.get}'s body */
    static List<IntegrationState> integrations(MsgValue body) {
        return WireValues.array(body, "services").stream().filter(WireValues::isMap)
                .flatMap(i -> integration(i).stream()).toList();
    }

    /** @return one {@code Integration}, as {@code alerts.integrations.set} answers it */
    static Optional<IntegrationState> integration(MsgValue i) {
        return service(i).map(service -> new IntegrationState(service, WireValues.bool(i, "hasSecret", false),
                WireValues.string(i, "secretHint"), WireValues.bool(i, "mentionHere", false),
                WireValues.string(i, "server", ""), WireValues.string(i, "topic", "")));
    }

    /** @return {@code alerts.integrations.test}'s body */
    static MsgValue test(AlertService service) {
        return MsgMap.builder().put(key("service"), MsgValue.of(service.id())).build();
    }

    /** @return the id in {@code alerts.integrations.test}'s answer */
    static OptionalLong testDeliveryId(MsgValue body) {
        return WireValues.integer(body, "deliveryId");
    }

    /** @return the services in {@code alerts.status}'s body */
    static List<ServiceState> status(MsgValue body) {
        return WireValues.array(body, "services").stream().filter(WireValues::isMap)
                .flatMap(s -> service(s).map(service -> new ServiceState(service,
                        WireValues.bool(s, "configured", false), WireValues.bool(s, "sending", false),
                        (int) WireValues.integer(s, "queued", 0L),
                        WireValues.field(s, "last").filter(WireValues::isMap)
                                .flatMap(last -> result(service, last)))).stream())
                .toList();
    }

    /** @return the {@code alerts.result} event's body; empty when it names an unknown service */
    static Optional<AlertResult> result(MsgValue body) {
        return service(body).flatMap(service -> result(service, body));
    }

    private static Optional<AlertResult> result(AlertService service, MsgValue r) {
        Optional<AlertResult.Outcome> outcome = resultOutcome(WireValues.string(r, "outcome", ""));
        return outcome.map(o -> new AlertResult(WireValues.integer(r, "deliveryId", 0L), service,
                WireValues.bool(r, "isTest", false), WireValues.string(r, "kind", ""), o,
                WireValues.string(r, "status", ""), WireValues.string(r, "reason", ""),
                (int) WireValues.integer(r, "attempts", 0L),
                Duration.ofMillis(Math.max(0L, WireValues.integer(r, "elapsedMs", 0L))),
                Instant.ofEpochMilli(WireValues.integer(r, "ts", 0L))));
    }

    /** {@code delivered}, {@code failed} or {@code stale}; also a status's {@code last.outcome}. */
    private static Optional<AlertResult.Outcome> resultOutcome(String wire) {
        return switch (wire) {
            case "delivered" -> Optional.of(AlertResult.Outcome.DELIVERED);
            case "failed" -> Optional.of(AlertResult.Outcome.FAILED);
            case "stale" -> Optional.of(AlertResult.Outcome.STALE);
            default -> Optional.empty();
        };
    }

    private static DeliveryOutcome outcome(AlertService service, MsgValue r) {
        boolean isQueued = WireValues.string(r, "outcome", "").equals(OUTCOME_QUEUED);
        Optional<DeliveryOutcome.DropReason> reason = isQueued ? Optional.empty()
                : Optional.of(dropReason(WireValues.string(r, "reason", "")));
        return new DeliveryOutcome(service, isQueued, WireValues.integer(r, "deliveryId"), reason);
    }

    private static DeliveryOutcome.DropReason dropReason(String wire) {
        return switch (wire) {
            case "not_configured" -> DeliveryOutcome.DropReason.NOT_CONFIGURED;
            case "queue_full" -> DeliveryOutcome.DropReason.QUEUE_FULL;
            case "duplicate" -> DeliveryOutcome.DropReason.DUPLICATE;
            default -> DeliveryOutcome.DropReason.UNKNOWN;
        };
    }

    private static Optional<AlertService> service(MsgValue map) {
        return WireValues.string(map, "service").flatMap(AlertService::fromId);
    }

    /** @return {@code text} cut to at most {@code max} code points */
    static String capCodePoints(String text, int max) {
        if (text.codePointCount(0, text.length()) <= max) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, max));
    }

    /** @return {@code text} cut to at most {@code max} UTF-8 bytes, never inside a code point */
    static String capUtf8(String text, int max) {
        if (text.getBytes(StandardCharsets.UTF_8).length <= max) {
            return text;
        }
        StringBuilder kept = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < text.length(); i = text.offsetByCodePoints(i, 1)) {
            String point = new String(Character.toChars(text.codePointAt(i)));
            bytes += point.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > max) {
                break;
            }
            kept.append(point);
        }
        return kept.toString();
    }

    private static MsgValue key(String name) {
        return MsgValue.of(name);
    }
}
