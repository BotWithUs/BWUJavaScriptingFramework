package com.botwithus.bot.api.hud;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.ComponentOp;
import com.botwithus.bot.api.model.ComponentOps;
import com.botwithus.bot.api.model.EnumType;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.util.Interfaces;
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
 * Ribbon tabs. The fixture enum puts the Skills window (0) on slot 31 rather than its
 * fallback 8, and the slot lists Open on op 3, so a press that ignored the enum, used op 1
 * or targeted the wrong component queues a different action from the one asserted.
 */
class HudTest {

    private static final int RIBBON = 1431;
    private static final int BUTTONS = 0;
    private static final int SKILLS_SLOT = 31;
    private static final int BACKPACK_SLOT = 9;
    private static final int RIBBON_WINDOWS = 13319;

    private final GameAPI api = mock(GameAPI.class);
    private final Hud hud = new Hud(api);

    @BeforeEach
    void setUp() {
        when(api.gamevals()).thenReturn(GamevalIndex.empty());
        when(api.getComponentOps(anyInt(), anyInt(), anyInt())).thenReturn(ComponentOps.NONE);
        when(api.getEnumType(RIBBON_WINDOWS)).thenReturn(new EnumType(RIBBON_WINDOWS, 0, 0, -1, "",
                2, Map.of(Integer.toString(SKILLS_SLOT), 0, Integer.toString(BACKPACK_SLOT), 2)));
        when(api.getComponentOps(RIBBON, BUTTONS, SKILLS_SLOT)).thenReturn(ops(3, Hud.OPEN));
        when(api.getComponentOps(RIBBON, BUTTONS, BACKPACK_SLOT)).thenReturn(ops(1, Hud.CLOSE));
    }

    @Test
    void slotOf_readsRibbonWindows_andFallsBackWithoutIt() {
        assertEquals(SKILLS_SLOT, hud.slotOf(HudTab.SKILLS));
        assertEquals(HudTab.PRAYER.fallbackSlot(), hud.slotOf(HudTab.PRAYER));
        when(api.getEnumType(RIBBON_WINDOWS)).thenReturn(null);
        assertEquals(HudTab.SKILLS.fallbackSlot(), hud.slotOf(HudTab.SKILLS));
    }

    @Test
    void open_pressesTheSlotsOpenOp() {
        assertFalse(hud.isOpen(HudTab.SKILLS));
        assertTrue(hud.open(HudTab.SKILLS));

        verify(api).queueAction(new GameAction(ActionTypes.COMPONENT, 3, SKILLS_SLOT,
                Interfaces.componentHash(RIBBON, BUTTONS)));
    }

    @Test
    void open_showingWindow_pressesNothing_soTheButtonDoesNotCloseIt() {
        assertTrue(hud.isOpen(HudTab.BACKPACK));
        assertTrue(hud.open(HudTab.BACKPACK));
        verify(api, never()).queueAction(any());
    }

    @Test
    void open_slotWithNeitherOption_isFalse() {
        assertFalse(hud.open(HudTab.MAGIC));
        assertFalse(hud.isOpen(HudTab.MAGIC));
        verify(api, never()).queueAction(any());
    }

    @Test
    void logoutAndExitToLobby_delegateToTheAgentCalls() {
        when(api.logout()).thenReturn(true);
        when(api.exitToLobby()).thenReturn(false);

        assertTrue(hud.logout());
        assertFalse(hud.exitToLobby());
        verify(api).logout();
        verify(api).exitToLobby();
    }

    private static ComponentOps ops(int op, String name) {
        return new ComponentOps(1 << op, List.of(new ComponentOp(op, name, true)));
    }
}
