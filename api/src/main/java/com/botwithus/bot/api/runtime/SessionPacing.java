package com.botwithus.bot.api.runtime;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * What the agent's session layer has planned for the timing of this client's actions.
 *
 * <p>When the layer is active, the agent decides <em>when</em> each queued action is
 * pressed: it holds the action until the planned press time, and it plans idle cursor runs
 * and breaks itself. Host-side pacing on top of that compounds with the plan, so the
 * host's own humanised delays ({@code Humanize.loopDelay}, {@code Humanize.sleep}) step
 * back while {@link #isActive()} is {@code true}. An older agent, or an agent without the
 * layer, reports {@link #INACTIVE}, and nothing changes.</p>
 *
 * <p>Read-only: a script sees the plan, it does not get to set timing.</p>
 *
 * @param isActive      whether the agent's session layer owns action timing
 * @param nextPressInMs milliseconds until the held action's planned press, break included;
 *                      {@code 0} when no action is held under the plan
 * @param plannedBreak  the nearest planned break: the one inside the held action, or else
 *                      the one already planned for the next action, when there is one
 */
public record SessionPacing(boolean isActive, long nextPressInMs, Optional<PlannedBreak> plannedBreak) {

    /** The layer is absent or off: host pacing behaves exactly as it always has. */
    public static final SessionPacing INACTIVE = new SessionPacing(false, 0, Optional.empty());

    /**
     * The calling script's plan source, bound by the runtime for the life of a script run so
     * the static {@code Humanize} helpers can find it. Unbound off a script thread.
     */
    public static final ScopedValue<Supplier<SessionPacing>> CURRENT = ScopedValue.newInstance();

    public SessionPacing {
        if (nextPressInMs < 0) {
            throw new IllegalArgumentException("nextPressInMs must be >= 0: " + nextPressInMs);
        }
        if (plannedBreak == null) {
            throw new IllegalArgumentException("plannedBreak must not be null; use Optional.empty()");
        }
    }

    /**
     * The calling script's current plan, read from its client; {@link #INACTIVE} off a
     * script thread.
     */
    public static SessionPacing current() {
        return CURRENT.isBound() ? CURRENT.get().get() : INACTIVE;
    }
}
