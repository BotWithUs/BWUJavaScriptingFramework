package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.TestContexts;
import com.botwithus.bot.cli.command.CommandParser;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.core.runtime.ScriptRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReloadCommandGroupTest {

    @TempDir
    Path tempDir;

    private ReloadCommand command;
    private CliContext ctx;
    private ByteArrayOutputStream output;

    @BeforeEach
    void setUp() {
        command = new ReloadCommand();
        output = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(output);
        ctx = spy(TestContexts.inDir(tempDir, ps));
    }

    private ParsedCommand parse(String input) {
        return CommandParser.parse(input);
    }

    private String output() {
        return output.toString();
    }

    private Connection mockConnection(String name) {
        Connection conn = mock(Connection.class);
        when(conn.getName()).thenReturn(name);
        // Tests use the same name for a client's pipe and its account.
        when(conn.getIdentifiedUuid()).thenReturn(Optional.of(name));
        when(conn.isAlive()).thenReturn(true);
        ScriptRuntime runtime = mock(ScriptRuntime.class);
        when(conn.getRuntime()).thenReturn(runtime);
        return conn;
    }

    @Test
    void reloadWithGroupNonExistent() {
        command.execute(parse("reload --group=nope"), ctx);
        assertTrue(output().contains("Group not found"));
    }

    @Test
    void reloadWithGroupNoActiveConnections() {
        TestContexts.createGroup(ctx, "farm");
        TestContexts.addMember(ctx, "farm", "Bot1");
        command.execute(parse("reload --group=farm"), ctx);
        assertTrue(output().contains("No active connections"));
    }

    @Test
    void reloadWithGroupCallsStopAllAndRegister() {
        TestContexts.createGroup(ctx, "farm");
        TestContexts.addMember(ctx, "farm", "Bot1");

        Connection conn1 = mockConnection("Bot1");
        doReturn(List.of(conn1)).when(ctx).getGroupConnections("farm");

        BotScript mockScript = mock(BotScript.class);
        doReturn(List.of(mockScript)).when(ctx).loadScripts();

        command.execute(parse("reload --group=farm"), ctx);

        verify(conn1.getRuntime()).stopAll();
        verify(conn1.getRuntime()).registerScript(mockScript);

        String out = output();
        assertTrue(out.contains("[Bot1] Reloading"));
        assertTrue(out.contains("[Bot1] Discovered 1 script(s)"));
    }

    @Test
    void reloadWithGroupAndStartFlag() {
        TestContexts.createGroup(ctx, "farm");
        TestContexts.addMember(ctx, "farm", "Bot1");

        Connection conn1 = mockConnection("Bot1");
        doReturn(List.of(conn1)).when(ctx).getGroupConnections("farm");

        BotScript mockScript = mock(BotScript.class);
        List<BotScript> scripts = List.of(mockScript);
        doReturn(scripts).when(ctx).loadScripts();

        command.execute(parse("reload --group=farm --start"), ctx);

        verify(conn1.getRuntime()).stopAll();
        verify(conn1.getRuntime()).startAll(scripts);
        assertTrue(output().contains("[Bot1] Started 1 script(s)"));
    }

    @Test
    void reloadWithGroupWarnsDisconnected() {
        TestContexts.createGroup(ctx, "farm");
        TestContexts.addMember(ctx, "farm", "Bot1");
        TestContexts.addMember(ctx, "farm", "Bot2");

        Connection conn1 = mockConnection("Bot1");
        doReturn(List.of(conn1)).when(ctx).getGroupConnections("farm");
        doReturn(List.of()).when(ctx).loadScripts();

        command.execute(parse("reload --group=farm"), ctx);

        String out = output();
        assertTrue(out.contains("[Bot2]") && out.contains("disconnected"));
    }

    @Test
    void reloadWithGroupMultipleConnections() {
        TestContexts.createGroup(ctx, "farm");
        TestContexts.addMember(ctx, "farm", "Bot1");
        TestContexts.addMember(ctx, "farm", "Bot2");

        Connection conn1 = mockConnection("Bot1");
        Connection conn2 = mockConnection("Bot2");
        doReturn(List.of(conn1, conn2)).when(ctx).getGroupConnections("farm");

        BotScript mockScript = mock(BotScript.class);
        doReturn(List.of(mockScript)).when(ctx).loadScripts();

        command.execute(parse("reload --group=farm"), ctx);

        verify(conn1.getRuntime()).stopAll();
        verify(conn2.getRuntime()).stopAll();
        verify(conn1.getRuntime()).registerScript(mockScript);
        verify(conn2.getRuntime()).registerScript(mockScript);

        String out = output();
        assertTrue(out.contains("[Bot1] Discovered 1 script(s)"));
        assertTrue(out.contains("[Bot2] Discovered 1 script(s)"));
    }

    @Test
    void reloadWithoutGroupUsesActiveConnection() {
        // No --group, no active connection
        command.execute(parse("reload"), ctx);
        assertTrue(output().contains("No active connection"));
    }
}
