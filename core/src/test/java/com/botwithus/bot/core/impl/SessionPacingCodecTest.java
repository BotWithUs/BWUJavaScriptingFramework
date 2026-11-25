package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.runtime.PlannedBreak;
import com.botwithus.bot.api.runtime.PlannedBreak.Carrier;
import com.botwithus.bot.api.runtime.SessionPacing;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code get_humanizer_session} reply as the agent's wire note describes it, decoded for scripts. */
class SessionPacingCodecTest {

    private static final long NOW = 5_000_000L;
    private static final long PRESS_IN = 840L;
    private static final long BREAK_IN = 300L;
    private static final long BREAK_LEN = 45_000L;
    private static final long NEXT_BREAK = 61_000L;
    private static final long ALREADY_INTO_BREAK = 1_000L;
    private static final long PERSONA_SEED = 1_234_567_890_123L;

    private static Map<String, Object> activeReply() {
        Map<String, Object> reply = new HashMap<>();
        reply.put("active", true);
        reply.put("mode", "active");
        reply.put("now_ms", NOW);
        reply.put("held", false);
        reply.put("press_ms", 0L);
        reply.put("break_ms", 0);
        reply.put("break_start_ms", 0L);
        reply.put("break_end_ms", 0L);
        reply.put("next_break_ms", 0);
        reply.put("persona_seed", PERSONA_SEED);
        reply.put("counters", Map.of("planned", 1));
        return reply;
    }

    private static Map<String, Object> heldWithBreak(long breakStart) {
        Map<String, Object> reply = activeReply();
        reply.put("held", true);
        reply.put("break_ms", BREAK_LEN);
        reply.put("break_start_ms", breakStart);
        reply.put("break_end_ms", breakStart + BREAK_LEN);
        reply.put("press_ms", breakStart + BREAK_LEN + PRESS_IN);
        return reply;
    }

    @Test
    void decode_inactiveReply_isExactlyInactive() {
        Map<String, Object> reply = activeReply();
        reply.put("active", false);
        reply.put("mode", "absent");

        assertSame(SessionPacing.INACTIVE, SessionPacingCodec.decode(reply));
    }

    @Test
    void decode_emptyReply_isInactive() {
        assertSame(SessionPacing.INACTIVE, SessionPacingCodec.decode(Map.of()));
    }

    @Test
    void decode_activeNothingHeld_hasNoPressAndNoBreak() {
        SessionPacing pacing = SessionPacingCodec.decode(activeReply());

        assertTrue(pacing.isActive());
        assertEquals(0, pacing.nextPressInMs());
        assertEquals(Optional.empty(), pacing.plannedBreak());
    }

    @Test
    void decode_heldAction_pressIsADeltaOnTheClientClock() {
        Map<String, Object> reply = activeReply();
        reply.put("held", true);
        reply.put("press_ms", NOW + PRESS_IN);

        assertEquals(PRESS_IN, SessionPacingCodec.decode(reply).nextPressInMs());
    }

    @Test
    void decode_pressAlreadyDue_isZeroNotNegative() {
        Map<String, Object> reply = activeReply();
        reply.put("held", true);
        reply.put("press_ms", NOW - PRESS_IN);

        assertEquals(0, SessionPacingCodec.decode(reply).nextPressInMs());
    }

    @Test
    void decode_breakAheadInsideTheHold_isAHeldActionBreak() {
        SessionPacing pacing = SessionPacingCodec.decode(heldWithBreak(NOW + BREAK_IN));

        assertEquals(Optional.of(new PlannedBreak(Carrier.HELD_ACTION, BREAK_IN, BREAK_LEN)),
                pacing.plannedBreak());
        assertEquals(BREAK_IN + BREAK_LEN + PRESS_IN, pacing.nextPressInMs());
    }

    @Test
    void decode_breakUnderway_startsNowAndReportsWhatIsLeft() {
        SessionPacing pacing = SessionPacingCodec.decode(heldWithBreak(NOW - ALREADY_INTO_BREAK));

        PlannedBreak underway = pacing.plannedBreak().orElseThrow();
        assertEquals(0, underway.startsInMs());
        assertEquals(BREAK_LEN - ALREADY_INTO_BREAK, underway.lengthMs());
    }

    @Test
    void decode_breakOver_isNotReported() {
        SessionPacing pacing = SessionPacingCodec.decode(heldWithBreak(NOW - 2 * BREAK_LEN));

        assertFalse(pacing.plannedBreak().isPresent());
    }

    @Test
    void decode_nextActionBreak_isVisibleBeforeDispatch() {
        Map<String, Object> reply = activeReply();
        reply.put("next_break_ms", NEXT_BREAK);

        assertEquals(Optional.of(new PlannedBreak(Carrier.NEXT_ACTION, 0, NEXT_BREAK)),
                SessionPacingCodec.decode(reply).plannedBreak());
    }

    @Test
    void decode_heldBreakWins_overTheNextActionBreak() {
        Map<String, Object> reply = heldWithBreak(NOW + BREAK_IN);
        reply.put("next_break_ms", NEXT_BREAK);

        assertEquals(Carrier.HELD_ACTION,
                SessionPacingCodec.decode(reply).plannedBreak().orElseThrow().carrier());
    }

    @Test
    void decode_clockUnreadable_keepsTheBreakLengthButNoDeltas() {
        Map<String, Object> reply = heldWithBreak(NOW + BREAK_IN);
        reply.put("now_ms", 0L);

        SessionPacing pacing = SessionPacingCodec.decode(reply);

        assertTrue(pacing.isActive());
        assertEquals(0, pacing.nextPressInMs());
        assertEquals(Optional.of(new PlannedBreak(Carrier.HELD_ACTION, 0, BREAK_LEN)),
                pacing.plannedBreak());
    }
}
