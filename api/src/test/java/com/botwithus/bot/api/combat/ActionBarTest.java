package com.botwithus.bot.api.combat;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.EnumType;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.ScriptResult;
import com.botwithus.bot.api.model.StructType;
import com.botwithus.bot.api.model.VarbitValue;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ActionBar} over a mocked game: bar 3 active, slots holding an ability, an item, an
 * empty slot and a type the cache does not know. The ids (struct 14682 = Backhand under melee
 * type 1, enum 10147, type-to-enum 13199) are the real ones, so a decode that read the type
 * and id varbits the wrong way round, or looked the ability up in the wrong enum, fails.
 */
class ActionBarTest {

    private static final int BAR = 3;
    private static final int MELEE_TYPE = 1;
    private static final int MELEE_ENUM = 10147;
    private static final int BACKHAND_ID = 3;
    private static final int BACKHAND_STRUCT = 14682;
    private static final int TYPE_TO_ENUM = 13199;
    private static final int LOBSTER = 379;
    private static final int UNKNOWN_TYPE = 99;
    private static final int SLOT_ONE_BUTTON = (1430 << 16) | 65;

    private final GameAPI api = mock(GameAPI.class);
    private final MapGamevalIndex gamevals = new MapGamevalIndex();
    private final ActionBar bar = new ActionBar(api);

    @BeforeEach
    void setUp() {
        when(api.gamevals()).thenReturn(gamevals);
        gamevals.put(GamevalType.VARBIT, "COMBATV2_ACTIONBAR_CURRENT_BAR", 1893);
        gamevals.put(GamevalType.ENUM, "COMBATV2_TYPE_TO_ENUM", TYPE_TO_ENUM);
        gamevals.put(GamevalType.COMPONENT, "TOPLEVEL_V2_COMBAT_BAR__BUTTON_1", SLOT_ONE_BUTTON);
        when(api.getVarbit(1893)).thenReturn(BAR);
        registerSlotVars();
        when(api.getEnumType(TYPE_TO_ENUM)).thenReturn(enumOf(TYPE_TO_ENUM,
                Map.of("1", MELEE_ENUM)));
        when(api.getEnumType(MELEE_ENUM)).thenReturn(enumOf(MELEE_ENUM,
                Map.of(Integer.toString(BACKHAND_ID), BACKHAND_STRUCT)));
        when(api.getStructType(BACKHAND_STRUCT)).thenReturn(new StructType(BACKHAND_STRUCT,
                Map.of("2793", BACKHAND_ID, "2794", "Backhand", "2796", 25)));
    }

    @Test
    void slots_decodeAbilityItemEmptyAndUnknown() {
        List<ActionBarSlot> slots = bar.slots();

        assertEquals(ActionBar.SLOTS, slots.size());
        ActionBarSlot.AbilitySlot first = assertInstanceOf(ActionBarSlot.AbilitySlot.class, slots.get(0));
        assertEquals(1, first.slot());
        assertEquals("Backhand", first.ability().name());
        assertEquals(25, first.ability().cooldownTicks());
        assertEquals(Ability.DEFAULT_GLOBAL_COOLDOWN_TICKS, first.ability().globalCooldownTicks());
        assertEquals(LOBSTER, assertInstanceOf(ActionBarSlot.ItemSlot.class, slots.get(1)).itemId());
        assertInstanceOf(ActionBarSlot.Empty.class, slots.get(2));
        ActionBarSlot.Unresolved odd = assertInstanceOf(ActionBarSlot.Unresolved.class, slots.get(3));
        assertEquals(UNKNOWN_TYPE, odd.typeCode());
    }

    @Test
    void useAbility_pressesTheSlotsButton_byName() {
        assertTrue(bar.containsAbility("BACKHAND"));
        assertTrue(bar.useAbility("backhand"));

        verify(api).queueAction(new GameAction(ActionTypes.COMPONENT, 1, -1, SLOT_ONE_BUTTON));
    }

    @Test
    void useAbility_notOnTheBar_queuesNothing() {
        assertFalse(bar.useAbility("Slice"));

        verify(api, never()).queueAction(any());
    }

    @Test
    void cooldown_runsTheTwoClientScripts_andReadsTheWindow() {
        when(api.getScriptHandle(ActionBar.SCRIPT_COOLDOWN_STRUCT)).thenReturn(11L);
        when(api.getScriptHandle(ActionBar.SCRIPT_COOLDOWN_WINDOW)).thenReturn(12L);
        when(api.executeScript(eq(11L), any(), any(), any()))
                .thenReturn(new ScriptResult(List.of(BACKHAND_STRUCT)));
        when(api.executeScript(eq(12L), any(), any(), any()))
                .thenReturn(new ScriptResult(List.of(10_000, 10_750)));
        GameSnapshot snapshot = mock(GameSnapshot.class);
        when(snapshot.gameCycle()).thenReturn(10_500);
        when(api.snapshot()).thenReturn(snapshot);
        Ability backhand = bar.catalog().resolve(MELEE_TYPE, BACKHAND_ID).orElseThrow();

        Cooldown window = bar.cooldown(backhand);

        assertTrue(window.isKnown());
        assertEquals(10_750, window.endCycle());
        assertEquals(250 * Cooldown.CLIENT_CYCLE_MS, bar.cooldownRemainingMs(backhand));
        assertFalse(window.isReady(10_500));
        assertTrue(window.isReady(10_750));
        // Two reads above (cooldown, cooldownRemainingMs): every handle taken is released.
        verify(api, times(2)).destroyScriptHandle(11L);
        verify(api, times(2)).destroyScriptHandle(12L);
    }

    @Test
    void cooldown_whenTheScriptFails_isUnknownNotReady() {
        when(api.getScriptHandle(ActionBar.SCRIPT_COOLDOWN_STRUCT))
                .thenThrow(new IllegalStateException("no handle"));
        Ability backhand = bar.catalog().resolve(MELEE_TYPE, BACKHAND_ID).orElseThrow();

        Cooldown window = bar.cooldown(backhand);

        assertFalse(window.isKnown());
        assertFalse(window.isReady(Integer.MAX_VALUE));
        assertEquals(-1, window.remainingCycles(0));
    }

    @Test
    void noGamevalIndex_meansNoSlotsRatherThanGuessedIds() {
        when(api.gamevals()).thenReturn(GamevalIndex.empty());

        assertTrue(bar.slotsOf(BAR).isEmpty());
        verify(api, never()).queryVarbits(anyList());
    }

    private void registerSlotVars() {
        List<VarbitValue> values = new ArrayList<>();
        List<Integer> itemVarps = new ArrayList<>();
        List<Integer> varbitIds = new ArrayList<>();
        List<Integer> items = new ArrayList<>();
        int[][] slots = {{MELEE_TYPE, BACKHAND_ID, -1}, {0, 0, LOBSTER}, {0, 0, -1},
                {UNKNOWN_TYPE, 5, -1}};
        for (int slot = 1; slot <= ActionBar.SLOTS; slot++) {
            String base = "ACTIONBAR" + BAR + "_INT" + slot;
            int typeId = 50_000 + slot * 2;
            gamevals.put(GamevalType.VARBIT, base + "_TYPE", typeId);
            gamevals.put(GamevalType.VARBIT, base + "_ID", typeId + 1);
            gamevals.put(GamevalType.VARP, "ACTIONBAR" + BAR + "_OBJ" + slot, 60_000 + slot);
            int[] content = slot <= slots.length ? slots[slot - 1] : new int[] {0, 0, -1};
            varbitIds.add(typeId);
            varbitIds.add(typeId + 1);
            values.add(new VarbitValue(typeId, content[0]));
            values.add(new VarbitValue(typeId + 1, content[1]));
            itemVarps.add(60_000 + slot);
            items.add(content[2]);
        }
        when(api.queryVarbits(varbitIds)).thenReturn(values);
        when(api.getVarps(itemVarps)).thenReturn(items);
    }

    private static EnumType enumOf(int id, Map<String, Object> entries) {
        return new EnumType(id, 0, 0, -1, "", entries.size(), entries);
    }
}
