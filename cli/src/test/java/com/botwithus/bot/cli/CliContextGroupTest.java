package com.botwithus.bot.cli;

import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CliContextGroupTest {

    @TempDir
    Path tempDir;

    private CliContext ctx;
    private ByteArrayOutputStream outputStream;

    @BeforeEach
    void setUp() {
        outputStream = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(outputStream);
        LogBuffer logBuffer = new LogBuffer();
        LogCapture logCapture = new LogCapture(logBuffer, ps, ps);
        ctx = new CliContext(logBuffer, logCapture, tempDir.resolve("groups.json"));
    }

    @Test
    void createAndGetGroup() {
        ctx.createGroup("farm");
        assertNotNull(ctx.getGroup("farm"));
        assertEquals("farm", ctx.getGroup("farm").getName());
    }

    @Test
    void deleteGroup() {
        ctx.createGroup("farm");
        assertTrue(ctx.deleteGroup("farm"));
        assertNull(ctx.getGroup("farm"));
    }

    @Test
    void deleteNonExistentReturnsFalse() {
        assertFalse(ctx.deleteGroup("nope"));
    }

    @Test
    void getGroupsReturnsAllGroups() {
        ctx.createGroup("farm");
        ctx.createGroup("bosses");
        assertEquals(2, ctx.getGroups().size());
        assertTrue(ctx.getGroups().containsKey("farm"));
        assertTrue(ctx.getGroups().containsKey("bosses"));
    }

    @Test
    void getGroupsIsUnmodifiable() {
        ctx.createGroup("farm");
        assertThrows(UnsupportedOperationException.class, () ->
                ctx.getGroups().put("hack", new ConnectionGroup("hack")));
    }

    @Test
    void getGroupConnectionsReturnsEmptyForUnknownGroup() {
        List<Connection> conns = ctx.getGroupConnections("nope");
        assertTrue(conns.isEmpty());
    }

    @Test
    void getGroupConnectionsSkipsDisconnectedNames() {
        // Group has a name "Bot1" but no Connection is created for it
        ctx.createGroup("farm");
        ctx.getGroup("farm").add("Bot1");

        List<Connection> conns = ctx.getGroupConnections("farm");
        assertTrue(conns.isEmpty());
    }
}
