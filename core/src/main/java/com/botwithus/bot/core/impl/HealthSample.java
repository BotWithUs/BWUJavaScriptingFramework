package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.snapshot.LocalPlayer;

/**
 * One reading of the local player's life-point varps, tagged with the server
 * tick it was taken on.
 *
 * <p>Health is not published in the shared-memory snapshot, so
 * {@code GameAPIImpl.getLocalPlayer} pays a pipe round-trip for it. The tick
 * tag is what keeps that to one round-trip per 600ms server tick no matter how
 * often scripts ask: a sample whose tick still matches the snapshot's is
 * reused as-is. Values move only on a server tick, so a reused sample is
 * current, not merely fresh enough.</p>
 *
 * <p>The three fields travel together in one immutable object precisely so a
 * reader cannot observe a new tick beside stale values.</p>
 *
 * @param serverTick the snapshot server tick this reading was taken on
 * @param current    current life points, or {@link LocalPlayer#HEALTH_UNKNOWN}
 * @param max        maximum life points, or {@link LocalPlayer#HEALTH_UNKNOWN}
 */
record HealthSample(int serverTick, int current, int max) {

    /**
     * Reading for a tick whose varp read did not land — an unset variable, a
     * producer that truncated the batch, or a failed call. Not cached, so the
     * next call retries rather than serving this for the rest of the tick.
     */
    static HealthSample unknown(int serverTick) {
        return new HealthSample(serverTick, LocalPlayer.HEALTH_UNKNOWN, LocalPlayer.HEALTH_UNKNOWN);
    }

    /** True when both values came back as real readings. */
    boolean isKnown() {
        return current != LocalPlayer.HEALTH_UNKNOWN && max != LocalPlayer.HEALTH_UNKNOWN;
    }
}
