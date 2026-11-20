package com.botwithus.bot.api.dialog;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.util.Interfaces;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

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
 * Text, title and continue across the one-page dialogs, and the destroy confirmation. No
 * gameval index is deployed, so every component resolves to its fallback id. Each page keeps
 * its text on a different component, so reading the NPC page's ids for a message box (or the
 * reverse) returns nothing and fails the test.
 */
class DialogTextTest {

    private static final int NPC_CHAT = 1184;
    private static final int PLAYER_CHAT = 1191;
    private static final int MESSAGE_BOX = 1186;
    private static final int CONFIRM_DESTROY = 1183;

    private final GameAPI api = mock(GameAPI.class);

    @BeforeEach
    void setUp() {
        when(api.gamevals()).thenReturn(GamevalIndex.empty());
    }

    @Test
    void playerChat_textTitleAndContinue_readItsOwnComponents() {
        open(PLAYER_CHAT);
        text(PLAYER_CHAT, 10, "I'd like to buy a <col=ff0000>sword</col>.");
        text(PLAYER_CHAT, 4, "Zezima");

        assertTrue(Dialog.isPlayerChatOpen(api));
        assertEquals(Optional.of("I'd like to buy a <col=ff0000>sword</col>."), Dialog.getText(api));
        assertEquals(Optional.of("Zezima"), Dialog.getTitle(api));
        assertTrue(Dialog.continueDialog(api));
        verify(api).queueAction(new GameAction(ActionTypes.DIALOGUE, 0, -1,
                Interfaces.componentHash(PLAYER_CHAT, 15)));
    }

    @Test
    void npcChat_winsOverAMessageBoxOpenAtTheSameTime() {
        open(NPC_CHAT);
        open(MESSAGE_BOX);
        text(NPC_CHAT, 10, "Hello there!");
        text(MESSAGE_BOX, 3, "You hear a noise.");

        assertEquals(Optional.of("Hello there!"), Dialog.getText(api));
    }

    @Test
    void messageBox_hasTextButNoTitle() {
        open(MESSAGE_BOX);
        text(MESSAGE_BOX, 3, "Your bank is full.");

        assertEquals(Optional.of("Your bank is full."), Dialog.getText(api));
        assertEquals(Optional.empty(), Dialog.getTitle(api));
        assertTrue(Dialog.continueDialog(api));
        verify(api).queueAction(new GameAction(ActionTypes.DIALOGUE, 0, -1,
                Interfaces.componentHash(MESSAGE_BOX, 8)));
    }

    @Test
    void nothingOpen_readsEmptyAndQueuesNothing() {
        assertEquals(Optional.empty(), Dialog.getText(api));
        assertFalse(Dialog.canContinue(api));
        assertFalse(Dialog.continueDialog(api));
        verify(api, never()).getComponent(anyInt(), anyInt());
        verify(api, never()).queueAction(any());
    }

    @Test
    void plain_stripsMarkupAndKeepsCase() {
        assertEquals("Buy a sword now.", Dialog.plain("Buy a <col=ff0000>sword</col><br>now."));
    }

    @Test
    void confirmDestruction_pressesYesOrNo_onlyWhenOpen() {
        assertFalse(ConfirmItemDestruction.confirm(api));
        verify(api, never()).queueAction(any());

        open(CONFIRM_DESTROY);
        text(CONFIRM_DESTROY, 4, "<col=ff9040>Broken pot");

        assertEquals(Optional.of("Broken pot"), ConfirmItemDestruction.itemName(api));
        assertTrue(ConfirmItemDestruction.confirm(api));
        verify(api).queueAction(new GameAction(ActionTypes.DIALOGUE, 0, -1,
                Interfaces.componentHash(CONFIRM_DESTROY, 7)));
        assertTrue(ConfirmItemDestruction.cancel(api));
        verify(api).queueAction(new GameAction(ActionTypes.DIALOGUE, 0, -1,
                Interfaces.componentHash(CONFIRM_DESTROY, 8)));
    }

    private void open(int iface) {
        when(api.isInterfaceOpen(iface)).thenReturn(true);
    }

    private void text(int iface, int comp, String text) {
        when(api.getComponent(iface, comp)).thenReturn(new Component(iface, comp, -1, 0, 0, 0, 0,
                1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, text, 0, -1, -1, -1, List.of()));
    }
}
