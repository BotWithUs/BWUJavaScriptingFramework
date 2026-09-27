package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.core.alerts.AlertMessage;
import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.alerts.Notifier;
import com.botwithus.bot.core.alerts.SendResult;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A {@link NotifierSource} whose notifiers record what they were sent. Each service
 * is ready and delivers, unless a test makes it not ready or makes it fail.
 */
final class RecordingNotifiers implements NotifierSource {

    private final Map<AlertService, List<AlertMessage>> sent = new EnumMap<>(AlertService.class);
    private final Map<AlertService, String> notReady = new EnumMap<>(AlertService.class);
    private final Map<AlertService, Supplier<SendResult>> results = new EnumMap<>(AlertService.class);
    private final Clock clock;

    RecordingNotifiers(Clock clock) {
        this.clock = clock;
        for (AlertService service : AlertService.values()) {
            sent.put(service, new ArrayList<>());
        }
    }

    RecordingNotifiers notReady(AlertService service, String reason) {
        notReady.put(service, reason);
        return this;
    }

    RecordingNotifiers answer(AlertService service, Supplier<SendResult> result) {
        results.put(service, result);
        return this;
    }

    List<AlertMessage> sentTo(AlertService service) {
        return List.copyOf(sent.get(service));
    }

    @Override
    public NotifierSetup forService(AlertService service) {
        String reason = notReady.get(service);
        if (reason != null) {
            return new NotifierSetup.NotReady(reason);
        }
        return new NotifierSetup.Ready(new Notifier() {
            @Override
            public AlertService service() {
                return service;
            }

            @Override
            public SendResult send(AlertMessage message) {
                sent.get(service).add(message);
                Supplier<SendResult> result = results.get(service);
                return result != null ? result.get()
                        : new SendResult.Delivered(clock.instant(), Duration.ofMillis(120), 1);
            }
        });
    }
}
