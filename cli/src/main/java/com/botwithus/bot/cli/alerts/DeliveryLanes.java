package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.core.alerts.AlertService;

/**
 * Where sends run: one lane per service, so sends to a service go out in order
 * and a slow or rate-limited service never holds up another.
 * {@link VirtualDeliveryLanes} in the host; {@link #direct()} in tests.
 */
@FunctionalInterface
public interface DeliveryLanes {

    /** Queues {@code send} on {@code service}'s lane. */
    void submit(AlertService service, Runnable send);

    /** Runs every send at once on the calling thread. For tests. */
    static DeliveryLanes direct() {
        return (service, send) -> send.run();
    }
}
