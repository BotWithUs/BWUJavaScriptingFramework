package com.botwithus.bot.core.worldwalker;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which entry point a walk takes. The calls are fakes that record themselves, so
 * this runs without {@code worldwalker.dll}: an "old library" is a
 * {@link MovesEntry} built as not available, which is what
 * {@code WorldWalkerNative} builds when the symbol is missing.
 */
class MovesEntryTest {

    private static final int PLAIN_RC = 1;
    private static final int MASKED_RC = 2;
    private static final String PLAIN = "plain";

    private final List<String> calls = new ArrayList<>();

    @Test
    void noMask_takesThePlainEntry_evenWhenTheTwinExists() throws Throwable {
        int rc = call(new MovesEntry("ww_executor_run_ex", true), DisabledMoves.NONE);

        assertEquals(PLAIN_RC, rc);
        assertEquals(List.of(PLAIN), calls);
    }

    @Test
    void aMask_takesTheTwin_withTheMaskBitForBit() throws Throwable {
        int rc = call(new MovesEntry("ww_executor_run_ex", true), DisabledMoves.FREE_TO_PLAY);

        assertEquals(MASKED_RC, rc);
        assertEquals(List.of("masked:" + DisabledMoves.RESTRICT_FREE_TO_PLAY_BIT), calls);
    }

    @Test
    void aMask_againstAnOldLibrary_stillWalks_unrestricted() throws Throwable {
        MovesEntry old = new MovesEntry("ww_executor_run_ex", false);

        assertEquals(PLAIN_RC, call(old, DisabledMoves.FREE_TO_PLAY));
        assertEquals(PLAIN_RC, call(old, DisabledMoves.FREE_TO_PLAY));
        assertEquals(List.of(PLAIN, PLAIN), calls);
    }

    @Test
    void noMask_againstAnOldLibrary_takesThePlainEntry() throws Throwable {
        assertEquals(PLAIN_RC, call(new MovesEntry("ww_query_moves", false), DisabledMoves.NONE));
        assertEquals(List.of(PLAIN), calls);
    }

    private int call(MovesEntry entry, DisabledMoves moves) throws Throwable {
        return entry.call(moves,
                () -> {
                    calls.add(PLAIN);
                    return PLAIN_RC;
                },
                mask -> {
                    calls.add("masked:" + mask);
                    return MASKED_RC;
                });
    }
}
