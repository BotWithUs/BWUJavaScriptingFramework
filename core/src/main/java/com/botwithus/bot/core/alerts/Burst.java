package com.botwithus.bot.core.alerts;

import java.util.List;
import java.util.Objects;

/**
 * The alerts {@link BurstGrouper} collected into one message.
 *
 * @param alerts   the alerts kept, oldest first; never empty
 * @param overflow how many more arrived after the burst was full
 */
public record Burst(List<Alert> alerts, int overflow) {

    public Burst {
        alerts = List.copyOf(Objects.requireNonNull(alerts, "alerts"));
        if (alerts.isEmpty()) {
            throw new IllegalArgumentException("a burst holds at least one alert");
        }
        if (overflow < 0) {
            throw new IllegalArgumentException("overflow " + overflow);
        }
    }

    /** A burst of one alert. */
    public static Burst of(Alert alert) {
        return new Burst(List.of(alert), 0);
    }

    /** Every alert the burst stands for, including the overflow. */
    public int total() {
        return alerts.size() + overflow;
    }
}
