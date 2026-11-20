package com.botwithus.bot.api.combat;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.ComponentTreeNode;
import com.botwithus.bot.api.model.ComponentTrigger;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.StructType;
import com.botwithus.bot.api.model.VarbitValue;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.snapshot.Skill;
import com.botwithus.bot.api.util.SkillType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link Prayers} with no gameval index, so every name resolves to the fallback id the enum
 * carries — which are the current cache's ids, so this also pins them. Protect from Melee is
 * on and Piety off; a read that consulted the wrong varbit would flip one of them.
 */
class PrayersTest {

    private static final int PROTECT_MELEE_VARBIT = 16747;
    private static final int PIETY_VARBIT = 16757;
    private static final int TIERED_DEFENCE_STRUCT = 14540;
    private static final int ROCK_SKIN_STRUCT = 14542;
    private static final int PRAYER_WINDOW = 1458;
    private static final int PRAYER_BUTTONS = 40;
    private static final int ROCK_SKIN_SPRITE_OFF = 25984;

    private final GameAPI api = mock(GameAPI.class);
    private final GameSnapshot snapshot = mock(GameSnapshot.class);
    private final Prayers prayers = new Prayers(api);

    @BeforeEach
    void setUp() {
        when(api.gamevals()).thenReturn(GamevalIndex.empty());
        when(api.snapshot()).thenReturn(snapshot);
        when(snapshot.self()).thenReturn(selfWithPrayerLevel(15));
        when(api.getVarbit(PROTECT_MELEE_VARBIT)).thenReturn(1);
        when(api.getVarbit(PIETY_VARBIT)).thenReturn(0);
    }

    @Test
    void pointsAndMax_readTheVarbitAndTheLevel() {
        when(api.getVarbit(16736)).thenReturn(142);

        assertEquals(142, prayers.points());
        assertEquals(150, prayers.maxPoints());
    }

    @Test
    void isActive_readsEachPrayersOwnVarbit() {
        assertTrue(prayers.isActive(Prayer.PROTECT_MELEE));
        assertFalse(prayers.isActive(Prayer.PIETY));
    }

    @Test
    void isAnyActive_isOneBatch_andMatchesByPosition() {
        when(api.queryVarbits(List.of(PIETY_VARBIT, PROTECT_MELEE_VARBIT))).thenReturn(List.of(
                new VarbitValue(PIETY_VARBIT, 0), new VarbitValue(PROTECT_MELEE_VARBIT, 1)));

        assertTrue(prayers.isAnyActive(Prayer.PIETY, Prayer.PROTECT_MELEE));
        assertFalse(prayers.isAllActive(Prayer.PIETY, Prayer.PROTECT_MELEE));
    }

    @Test
    void activeBook_mapsPrayerMode() {
        when(api.getVarbit(16789)).thenReturn(1);
        assertEquals(PrayerBook.CURSES, prayers.activeBook());
        when(api.getVarbit(16789)).thenReturn(7);
        assertEquals(PrayerBook.UNKNOWN, prayers.activeBook());
    }

    @Test
    void tieredPrayer_isNamedAfterTheTierTheLevelSelects() {
        stubTieredDefence();

        PrayerInfo info = prayers.info(Prayer.TIERED_DEFENCE).orElseThrow();

        assertEquals("Rock Skin", info.name());
        assertEquals(ROCK_SKIN_STRUCT, info.structId());
        assertEquals(10, info.levelRequired());
    }

    @Test
    void toggle_clicksTheButtonShowingThePrayersSprite() {
        stubTieredDefence();
        when(api.isInterfaceOpen(PRAYER_WINDOW)).thenReturn(true);
        when(api.getInterfaceTree(PRAYER_WINDOW, 0)).thenReturn(List.of(
                node(PRAYER_BUTTONS, 0, 11111), node(PRAYER_BUTTONS, 4, ROCK_SKIN_SPRITE_OFF)));

        assertTrue(prayers.toggle(Prayer.TIERED_DEFENCE));

        verify(api).queueAction(new GameAction(ActionTypes.COMPONENT, 1, 4,
                (PRAYER_WINDOW << 16) | PRAYER_BUTTONS));
    }

    @Test
    void toggle_withTheWindowClosed_queuesNothing() {
        stubTieredDefence();
        when(api.isInterfaceOpen(anyInt())).thenReturn(false);

        assertFalse(prayers.toggle(Prayer.TIERED_DEFENCE));
        verify(api, never()).queueAction(any());
    }

    @Test
    void enumFallbackIds_matchTheCurrentCache() {
        assertEquals(TIERED_DEFENCE_STRUCT, Prayer.TIERED_DEFENCE.struct().fallbackId());
        assertEquals("COMBATV2_PRAYER_HEAL", Prayer.RAPID_HEAL.activeVarbit().gameval());
        assertEquals("CURSE_DEFLECT_RANGED", Prayer.CURSE_DEFLECT_MISSILES.activeVarbit().gameval());
        assertEquals(28, PrayerBook.NORMAL.prayers().size());
        assertEquals(45, PrayerBook.CURSES.prayers().size());
    }

    private void stubTieredDefence() {
        when(api.getStructType(TIERED_DEFENCE_STRUCT)).thenReturn(new StructType(
                TIERED_DEFENCE_STRUCT, Map.of("2963", 1, "2964", 14541, "2965", 10,
                "2966", ROCK_SKIN_STRUCT, "2967", 28, "2968", 14543)));
        when(api.getStructType(14541)).thenReturn(prayerStruct(14541, "Thick Skin", 1, 25983));
        when(api.getStructType(ROCK_SKIN_STRUCT)).thenReturn(
                prayerStruct(ROCK_SKIN_STRUCT, "Rock Skin", 10, ROCK_SKIN_SPRITE_OFF));
        when(api.getStructType(14543)).thenReturn(prayerStruct(14543, "Steel Skin", 28, 25985));
    }

    private static StructType prayerStruct(int id, String name, int level, int spriteOff) {
        return new StructType(id, Map.of("2794", name, "2807", level, "735", spriteOff + 100,
                "736", spriteOff));
    }

    private static ComponentTreeNode node(int comp, int sub, int sprite) {
        Component component = new Component(PRAYER_WINDOW, comp, sub, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, "", 0, sprite, -1, 0, List.of(new ComponentTrigger(1, 1)), -1,
                null, null);
        return new ComponentTreeNode(component, -1);
    }

    static LocalPlayer selfWithPrayerLevel(int level) {
        return new LocalPlayer(7, 50, 3200, 3200, 0, 0, -1, -1, -1, -1, 0, true, -1, 700, 990,
                List.of(new Skill(SkillType.PRAYER.id(), 2_411, level, level)));
    }
}
