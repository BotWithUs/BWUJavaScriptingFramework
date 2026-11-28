package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.runtime.PlannedBreak;
import com.botwithus.bot.api.runtime.PlannedBreak.Carrier;
import com.botwithus.bot.api.runtime.SessionPacing;

import java.util.Map;
import java.util.Optional;

/**
 * Decodes the agent's {@code get_humanizer_session} reply into the script-facing
 * {@link SessionPacing}.
 *
 * <p>Every absolute time in the reply is on the client's move clock, the clock the action
 * hold releases on, so each one is turned into a delta against the reply's own
 * {@code now_ms} and never against the host's clock. A {@code now_ms} of {@code 0} means the
 * agent could not read that clock: the deltas are then unknown, and the decode keeps only
 * what does not need one (the layer is active, and how long a planned break is).</p>
 *
 * <p>Only the fields a script needs are decoded. The persona, session and counter fields
 * are diagnostics for the agent's own tooling.</p>
 */
final class SessionPacingCodec {

    static final String METHOD = "get_humanizer_session";

    private static final String ACTIVE = "active";
    private static final String NOW_MS = "now_ms";
    private static final String HELD = "held";
    private static final String PRESS_MS = "press_ms";
    private static final String BREAK_MS = "break_ms";
    private static final String BREAK_START_MS = "break_start_ms";
    private static final String BREAK_END_MS = "break_end_ms";
    private static final String NEXT_BREAK_MS = "next_break_ms";
    /** {@code now_ms} when the agent could not read the client clock. */
    private static final long CLOCK_UNREADABLE = 0L;

    private SessionPacingCodec() {}

    static SessionPacing decode(Map<String, Object> reply) {
        if (!MapHelper.getBool(reply, ACTIVE)) {
            return SessionPacing.INACTIVE;
        }
        long now = MapHelper.getLong(reply, NOW_MS);
        boolean isHeld = MapHelper.getBool(reply, HELD);
        long nextPressInMs = isHeld ? untilOrZero(MapHelper.getLong(reply, PRESS_MS), now) : 0L;
        Optional<PlannedBreak> heldBreak = isHeld ? heldBreak(reply, now) : Optional.empty();
        Optional<PlannedBreak> plannedBreak = heldBreak.isPresent() ? heldBreak : nextBreak(reply);
        return new SessionPacing(true, nextPressInMs, plannedBreak);
    }

    private static Optional<PlannedBreak> heldBreak(Map<String, Object> reply, long now) {
        long breakMs = MapHelper.getLong(reply, BREAK_MS);
        if (breakMs <= 0) {
            return Optional.empty();
        }
        if (now == CLOCK_UNREADABLE) {
            return Optional.of(new PlannedBreak(Carrier.HELD_ACTION, 0L, breakMs));
        }
        long start = Math.max(MapHelper.getLong(reply, BREAK_START_MS), now);
        long remaining = MapHelper.getLong(reply, BREAK_END_MS) - start;
        if (remaining <= 0) {
            return Optional.empty();
        }
        return Optional.of(new PlannedBreak(Carrier.HELD_ACTION, start - now, remaining));
    }

    private static Optional<PlannedBreak> nextBreak(Map<String, Object> reply) {
        long nextBreakMs = MapHelper.getLong(reply, NEXT_BREAK_MS);
        if (nextBreakMs <= 0) {
            return Optional.empty();
        }
        return Optional.of(new PlannedBreak(Carrier.NEXT_ACTION, 0L, nextBreakMs));
    }

    /** Milliseconds from {@code now} until {@code at}; {@code 0} when either is unknown or past. */
    private static long untilOrZero(long at, long now) {
        if (now == CLOCK_UNREADABLE || at <= now) {
            return 0L;
        }
        return at - now;
    }
}
