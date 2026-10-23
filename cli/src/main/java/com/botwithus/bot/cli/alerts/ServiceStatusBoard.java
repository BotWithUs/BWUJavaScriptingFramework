package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.core.alerts.AlertService;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The last send to each service and which services have a test in flight, for the
 * Integrations status. Kept in memory: after a restart every service is Not tested
 * until something is sent. Thread-safe.
 */
public final class ServiceStatusBoard {

    private final Map<AlertService, LastSend> last = new ConcurrentHashMap<>();
    private final Set<AlertService> sending = ConcurrentHashMap.newKeySet();

    /** An empty board: nothing sent yet. */
    public ServiceStatusBoard() {
    }

    /** Records a finished send, and clears the in-flight mark if it was a test. */
    public void record(AlertService service, LastSend send) {
        last.put(service, send);
        if (send.isTest()) {
            sending.remove(service);
        }
    }

    /** Marks a test as in flight. */
    public void markSending(AlertService service) {
        sending.add(service);
    }

    /** The most recent send to {@code service}, if any. */
    public Optional<LastSend> last(AlertService service) {
        return Optional.ofNullable(last.get(service));
    }

    /** Whether a test to {@code service} is in flight. */
    public boolean isSending(AlertService service) {
        return sending.contains(service);
    }

    /** The status a card shows, given whether the service is switched on. */
    public ServiceStatus status(AlertService service, boolean isEnabled) {
        if (isSending(service)) {
            return ServiceStatus.SENDING;
        }
        if (!isEnabled) {
            return ServiceStatus.OFF;
        }
        return last(service)
                .map(send -> send.result().isDelivered() ? ServiceStatus.WORKING : ServiceStatus.FAILED)
                .orElse(ServiceStatus.NOT_TESTED);
    }
}
