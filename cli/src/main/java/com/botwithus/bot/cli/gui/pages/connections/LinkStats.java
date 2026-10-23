package com.botwithus.bot.cli.gui.pages.connections;

import java.time.Duration;
import java.util.Objects;

/**
 * How a live connection's pipe has been doing.
 *
 * @param uptime    how long the host has held the current pipe
 * @param calls     RPC calls made over it
 * @param errors    of those, how many failed
 * @param avgRpcMs  the mean RPC round trip, in milliseconds; {@code 0} before the first call
 */
public record LinkStats(Duration uptime, long calls, long errors, double avgRpcMs) {

    public LinkStats {
        Objects.requireNonNull(uptime, "uptime");
    }
}
