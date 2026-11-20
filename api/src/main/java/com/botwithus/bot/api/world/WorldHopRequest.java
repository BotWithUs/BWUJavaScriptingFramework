package com.botwithus.bot.api.world;

import java.util.Optional;

/**
 * The agent's answer to {@code set_world}: whether it queued the clicks that change world.
 *
 * <p><b>Predicate: {@link #started()}.</b> {@link #reason()} is the message, present only when
 * nothing was queued. {@code started} means the clicks are queued, not that the change
 * landed: whether the hop lands is the server's answer, which {@link Worlds#hop(int)} waits
 * for.</p>
 *
 * <p>An agent older than this reply answered {@code {}} and did nothing. That reads here as
 * not started with an empty reason, which {@link #refusal()} reports as
 * {@link HopRefusal#UNKNOWN}.</p>
 *
 * @param started true when the clicks are queued
 * @param reason  the agent's reason string when not started; empty otherwise
 */
public record WorldHopRequest(boolean started, String reason) {

    public WorldHopRequest {
        if (reason == null) {
            throw new IllegalArgumentException("reason");
        }
    }

    /** Why nothing was queued; empty when the clicks were queued. */
    public Optional<HopRefusal> refusal() {
        return started ? Optional.empty() : Optional.of(HopRefusal.fromWire(reason));
    }
}
