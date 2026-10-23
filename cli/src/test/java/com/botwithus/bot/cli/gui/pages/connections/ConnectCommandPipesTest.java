package com.botwithus.bot.cli.gui.pages.connections;

import com.botwithus.bot.cli.GameState;
import com.botwithus.bot.cli.GameStatus;
import com.botwithus.bot.cli.command.impl.ConnectCommand.PipeInfo;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** What a scan's probe of one pipe says about it, as a Found row shows it. */
class ConnectCommandPipesTest {

    private static final String PIPE = "BotWithUs_19544";
    private static final int WORLD = 102;
    private static final int NO_WORLD = -1;

    @Test
    void aLoggedInPipeIsInItsWorld() {
        FoundPipe found = ConnectCommandPipes.foundOf(new PipeInfo(PIPE, "Mirelock", WORLD, true, true));

        assertAll(
                () -> assertEquals(Optional.of("Mirelock"), found.account()),
                () -> assertEquals(new GameStatus(GameState.IN_GAME, OptionalInt.of(WORLD), true), found.game()));
    }

    @Test
    void aLoggedInPipeWithoutAWorldReplyHasNoWorld() {
        FoundPipe found = ConnectCommandPipes.foundOf(new PipeInfo(PIPE, "Mirelock", NO_WORLD, true, false));

        assertEquals(new GameStatus(GameState.IN_GAME, OptionalInt.empty(), false), found.game());
    }

    @Test
    void aNamedPipeThatIsNotLoggedInIsInTheLobby() {
        FoundPipe found = ConnectCommandPipes.foundOf(new PipeInfo(PIPE, "Mirelock", NO_WORLD, false, false));

        assertEquals(GameState.LOBBY, found.game().state());
    }

    @Test
    void aPipeTheProbeCouldNotReadIsUnknown() {
        FoundPipe blank = ConnectCommandPipes.foundOf(new PipeInfo(PIPE, null, NO_WORLD, false, false));
        FoundPipe timedOut = ConnectCommandPipes.foundOf(new PipeInfo(PIPE, "(timeout)", NO_WORLD, false, false));

        assertAll(
                () -> assertEquals(new FoundPipe(PIPE, Optional.empty(), GameStatus.UNKNOWN), blank),
                () -> assertEquals(new FoundPipe(PIPE, Optional.empty(), GameStatus.UNKNOWN), timedOut));
    }
}
