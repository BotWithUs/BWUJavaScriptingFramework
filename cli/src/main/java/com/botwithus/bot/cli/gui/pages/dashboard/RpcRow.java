package com.botwithus.bot.cli.gui.pages.dashboard;

import java.util.Objects;

/**
 * One RPC method's latency across every client in scope, pooled: the counts
 * are summed and the percentiles are taken over all the clients' samples at
 * once, so a slow client shows in the tail instead of being averaged away.
 */
public record RpcRow(String method, long calls, long errors, double avgMs,
                     double p50Ms, double p95Ms, double p99Ms) {

    /** A latency at or over this is drawn amber. */
    public static final double HOT_MS = 50.0;

    public RpcRow {
        Objects.requireNonNull(method, "method");
    }

    public static boolean isHot(double ms) {
        return ms >= HOT_MS;
    }

    /** The p50–p99 span is drawn amber when the typical slow call (p95) is hot. */
    public boolean isSpanHot() {
        return isHot(p95Ms);
    }
}
