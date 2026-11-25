package com.botwithus.bot.api.input;

import java.util.Objects;

/**
 * The client's focus mode as the agent reports it.
 *
 * @param mode            the mode in effect right now, never a requested mode that failed to apply
 * @param armed           whether the agent focus override is live right now; in practice
 *                        {@code mode == AGENT}
 * @param available       whether {@link FocusMode#AGENT} can be armed on this client at all
 * @param realFocusOnView whether the client's own thread really focuses the render view, without
 *                        the override. {@code false} means a key sent in {@link FocusMode#REAL}
 *                        is refused as {@link KeyResult#NOT_FOCUSED}. It is not "the client is the
 *                        foreground window": a client that was never activated can read
 *                        {@code true} while another application is in front.
 */
public record FocusState(FocusMode mode, boolean armed, boolean available, boolean realFocusOnView) {

    /** Rejects a {@code null} mode. */
    public FocusState {
        Objects.requireNonNull(mode, "mode");
    }
}
