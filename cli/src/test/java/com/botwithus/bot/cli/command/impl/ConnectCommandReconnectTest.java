package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.TestContexts;
import com.botwithus.bot.cli.command.CommandParser;
import com.botwithus.bot.cli.command.CommandResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectCommandReconnectTest {

    @TempDir
    Path dir;

    @Test
    void reconnect_withNoActiveConnection_saysSoAndConnectsNothing() {
        CliContext ctx = TestContexts.inDir(dir, new PrintStream(new ByteArrayOutputStream()));

        CommandResult result = assertDoesNotThrow(
                () -> new ConnectCommand().executeWithResult(CommandParser.parse("connect reconnect"), ctx),
                "reconnect with nothing active must answer, not throw");

        assertFalse(result.success());
        assertTrue(result.message().startsWith("No active connection to reconnect."), result.message());
        assertFalse(result.message().contains("pipeName"), "no internal parameter names in the message");
        assertTrue(ctx.getConnections().isEmpty());
    }
}
