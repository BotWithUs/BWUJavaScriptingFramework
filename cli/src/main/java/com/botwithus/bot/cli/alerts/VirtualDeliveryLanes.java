package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.core.alerts.AlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * {@link DeliveryLanes} with one virtual thread per service, each working through
 * its own queue in order.
 */
public final class VirtualDeliveryLanes implements DeliveryLanes, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(VirtualDeliveryLanes.class);

    private final Map<AlertService, ExecutorService> lanes = new EnumMap<>(AlertService.class);

    public VirtualDeliveryLanes() {
        for (AlertService service : AlertService.values()) {
            lanes.put(service, Executors.newSingleThreadExecutor(
                    Thread.ofVirtual().name("alerts-" + service.id()).factory()));
        }
    }

    @Override
    public void submit(AlertService service, Runnable send) {
        try {
            lanes.get(service).execute(send);
        } catch (RejectedExecutionException e) {
            log.debug("Alert lanes are closed; dropped a send to {}", service.label());
        }
    }

    /** Stops taking sends. Sends already queued still run; nothing waits for them. */
    @Override
    public void close() {
        lanes.values().forEach(ExecutorService::shutdown);
    }
}
