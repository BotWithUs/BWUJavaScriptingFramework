package com.botwithus.bot.api.buff;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.gameval.GamevalIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Buff state from varbits. Overload holds 17 steps and irit incense 3 steps at potency 2, so a
 * wrong unit, a swapped active/potency pair or a read of the wrong varbit gives a different
 * answer. Every varbit not stubbed reads 0 (off).
 */
class BuffsTest {

    private static final int OVERLOAD_VARBIT = 48834;
    private static final int IRIT_ACTIVE = 43714;
    private static final int IRIT_POTENCY = 43696;
    private static final int AGGRESSION_VARBIT = 33448;

    private final GameAPI api = mock(GameAPI.class);
    private final Buffs buffs = new Buffs(api);

    @BeforeEach
    void setUp() {
        when(api.gamevals()).thenReturn(GamevalIndex.empty());
        when(api.getVarbit(anyInt())).thenReturn(0);
    }

    @Test
    void timedBuff_remainingIsStepsTimesTheUnit() {
        when(api.getVarbit(OVERLOAD_VARBIT)).thenReturn(17);

        BuffState state = buffs.state(Buff.OVERLOAD);

        assertTrue(state.isActive());
        assertEquals(Optional.of(Duration.ofSeconds(255)), state.remaining());
        assertEquals(OptionalInt.empty(), state.potency());
        assertEquals(17, state.rawValue());
    }

    @Test
    void incense_readsItsPotencyFromTheSecondVarbit() {
        when(api.getVarbit(IRIT_ACTIVE)).thenReturn(3);
        when(api.getVarbit(IRIT_POTENCY)).thenReturn(2);

        BuffState state = buffs.state(Buff.IRIT_INCENSE);

        assertEquals(Optional.of(Duration.ofSeconds(45)), state.remaining());
        assertEquals(OptionalInt.of(2), state.potency());
    }

    @Test
    void flagBuff_isActiveWithNoTimeLeft() {
        when(api.getVarbit(AGGRESSION_VARBIT)).thenReturn(1);

        assertTrue(buffs.isActive(Buff.AGGRESSION));
        assertEquals(Optional.empty(), buffs.remaining(Buff.AGGRESSION));
    }

    @Test
    void unreadableOrZero_isOff_andActiveListsOnlyTheOnBuffs() {
        when(api.getVarbit(OVERLOAD_VARBIT)).thenReturn(Buffs.UNREAD);
        when(api.getVarbit(AGGRESSION_VARBIT)).thenReturn(1);

        assertFalse(buffs.isActive(Buff.OVERLOAD));
        assertEquals(Optional.empty(), buffs.remaining(Buff.OVERLOAD));
        assertEquals(List.of(Buff.AGGRESSION), buffs.active());
    }
}
