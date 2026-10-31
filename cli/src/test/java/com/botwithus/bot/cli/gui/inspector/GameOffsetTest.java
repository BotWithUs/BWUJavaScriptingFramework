package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.core.window.ClientArea;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Where a window pinned to a game sits on it, and how it stays inside the game. */
class GameOffsetTest {

    private static final ClientArea GAME = new ClientArea(100, 50, 800, 600);

    @Test
    void theDefaultPlace_isTheTopRightCorner_aMarginIn() {
        GameOffset offset = GameOffset.topRight(GAME, 200f, 100f, 10f);

        assertAll(
                () -> assertEquals(new GameOffset(590f, 10f), offset),
                () -> assertEquals(690f, offset.screenX(GAME)),
                () -> assertEquals(60f, offset.screenY(GAME)));
    }

    @Test
    void anOffsetFromTheDesktop_isRelativeToTheClientArea() {
        assertEquals(new GameOffset(40f, 30f), GameOffset.at(GAME, 140f, 80f));
    }

    @Test
    void anOffsetPastTheEdges_isPulledBackInside() {
        assertAll(
                () -> assertEquals(new GameOffset(600f, 500f),
                        new GameOffset(750f, 580f).clampedTo(GAME, 200f, 100f)),
                () -> assertEquals(new GameOffset(0f, 0f),
                        new GameOffset(-30f, -5f).clampedTo(GAME, 200f, 100f)));
    }

    @Test
    void aWindowLargerThanTheGame_keepsItsCornerOnTheGames() {
        assertEquals(new GameOffset(0f, 0f), new GameOffset(50f, 50f).clampedTo(GAME, 900f, 700f));
    }

    @Test
    void theDefaultPlace_ofAWindowWiderThanTheGame_isItsTopLeft() {
        assertEquals(new GameOffset(0f, 10f), GameOffset.topRight(GAME, 900f, 100f, 10f));
    }
}
