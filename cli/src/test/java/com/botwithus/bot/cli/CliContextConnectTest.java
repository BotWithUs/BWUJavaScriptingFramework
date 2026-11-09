package com.botwithus.bot.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CliContext#connect(String)} has no "any pipe" form. It used to take
 * {@code null} as "the first pipe found", which could be someone's own game.
 */
class CliContextConnectTest {

    @TempDir
    Path dir;

    @Test
    void connect_null_isRefused_neverResolvedToAPipe() {
        CliContext ctx = TestContexts.inDir(dir, new PrintStream(new ByteArrayOutputStream()));
        String none = null;

        NullPointerException refused = assertThrows(NullPointerException.class, () -> ctx.connect(none));

        assertTrue(refused.getMessage().contains("pipeName"), refused.getMessage());
        assertTrue(ctx.getConnections().isEmpty(), "nothing may be connected for a null name");
    }
}
