package com.botwithus.bot.api.hud;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.component.Components;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.ComponentOp;
import com.botwithus.bot.api.model.ComponentOps;
import com.botwithus.bot.api.model.ComponentTreeNode;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.util.Interfaces;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ribbon tabs and the Escape-menu leave calls. The skills button is a sub (7) of a ribbon
 * component and lists its option on op 2, so a press that ignored the sub or used op 1 queues a
 * different action from the one asserted.
 */
class HudTest {

    private static final int RIBBON = 1431;
    private static final int TAB_GRID = 6;
    private static final int SKILLS_SUB = 7;
    private static final int SKILLS_WINDOW = 1466;

    private final GameAPI api = mock(GameAPI.class);
    private final Hud hud = new Hud(api);

    @BeforeEach
    void setUp() {
        when(api.gamevals()).thenReturn(GamevalIndex.empty());
        when(api.components()).thenReturn(new Components(api));
        when(api.getComponentOps(anyInt(), anyInt(), anyInt())).thenReturn(ComponentOps.NONE);
        when(api.getInterfaceTree(RIBBON, 0)).thenReturn(List.of(
                new ComponentTreeNode(component(0, -1), -1),
                new ComponentTreeNode(component(TAB_GRID, SKILLS_SUB), 0)));
        when(api.getComponentOps(RIBBON, TAB_GRID, SKILLS_SUB)).thenReturn(new ComponentOps(1 << 2,
                List.of(new ComponentOp(2, "Open Skills", true))));
    }

    @Test
    void open_pressesTheRibbonButtonListingTheTabsOption() {
        assertTrue(hud.open(HudTab.SKILLS));

        verify(api).queueAction(new GameAction(ActionTypes.COMPONENT, 2, SKILLS_SUB,
                Interfaces.componentHash(RIBBON, TAB_GRID)));
    }

    @Test
    void open_alreadyOpen_pressesNothing_soTheToggleDoesNotCloseIt() {
        when(api.isInterfaceOpen(SKILLS_WINDOW)).thenReturn(true);

        assertTrue(hud.open(HudTab.SKILLS));
        assertTrue(hud.isOpen(HudTab.SKILLS));
        verify(api, never()).queueAction(any());
    }

    @Test
    void open_noButtonWithTheOption_isFalse() {
        assertFalse(hud.open(HudTab.BACKPACK));
        assertFalse(hud.isOpen(HudTab.BACKPACK));
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

    private static Component component(int comp, int sub) {
        return new Component(RIBBON, comp, sub, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, "", 0,
                -1, -1, -1, List.of());
    }
}
