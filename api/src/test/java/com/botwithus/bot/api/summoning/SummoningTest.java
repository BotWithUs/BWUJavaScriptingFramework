package com.botwithus.bot.api.summoning;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.constants.InventoryIds;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.ItemType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link Summoning} with no gameval index (fallback ids). The pouch is the real Pack yak pouch
 * (12093) with its real params; a familiar built from the wrong param would carry the wrong
 * NPC or level.
 */
class SummoningTest {

    private static final int FOLLOWER_OBJ = 1831;
    private static final int PACK_YAK_POUCH = 12093;
    private static final int PACK_YAK_NPC = 6873;

    private final GameAPI api = mock(GameAPI.class);
    private final Summoning summoning = new Summoning(api);

    @BeforeEach
    void setUp() {
        when(api.gamevals()).thenReturn(GamevalIndex.empty());
        when(api.getItemType(PACK_YAK_POUCH)).thenReturn(new ItemType(PACK_YAK_POUCH,
                "Pack yak pouch", true, false, 0, 0, 0, -1, -1, -1, -1, true, List.of(),
                List.of(), Map.of("74", PACK_YAK_NPC, "394", 96, "396", 1000)));
    }

    @Test
    void familiar_isReadFromThePouchParams() {
        when(api.getVarp(FOLLOWER_OBJ)).thenReturn(PACK_YAK_POUCH);

        Familiar yak = summoning.familiar().orElseThrow();

        assertEquals(PACK_YAK_NPC, yak.npcId());
        assertEquals(96, yak.levelRequired());
        assertEquals(1000, yak.summoningCost());
        assertEquals("Pack yak pouch", yak.pouchName());
    }

    @Test
    void varReads_useTheirOwnVariables() {
        when(api.getVarbit(41524)).thenReturn(612);
        when(api.getVarbit(6055)).thenReturn(43);
        when(api.getVarp(1787)).thenReturn(48);
        when(api.getVarbit(25412)).thenReturn(17);

        assertEquals(612, summoning.points());
        assertEquals(43, summoning.minutesRemaining());
        assertEquals(48, summoning.specialMovePoints());
        assertEquals(17, summoning.scrollsStored());
    }

    @Test
    void buttons_refuseWithNoFamiliar() {
        when(api.getVarp(FOLLOWER_OBJ)).thenReturn(-1);

        assertFalse(summoning.hasFamiliar());
        assertFalse(summoning.dismiss());
        verify(api, never()).queueAction(any());
    }

    @Test
    void dismiss_pressesTheCombatBarDismissOption() {
        when(api.getVarp(FOLLOWER_OBJ)).thenReturn(PACK_YAK_POUCH);

        assertTrue(summoning.dismiss());
        verify(api).queueAction(new GameAction(ActionTypes.COMPONENT, 1, -1, (1430 << 16) | 51));
    }

    @Test
    void beastOfBurden_isTheLoreBobInventory() {
        assertEquals(530, InventoryIds.FAMILIAR);
        assertEquals(530, summoning.beastOfBurden().invId());
    }
}
