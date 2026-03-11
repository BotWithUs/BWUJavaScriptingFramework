package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.command.CommandParser;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogCapture;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClientCommandTest {

    private ClientCommand command;
    private CliContext ctx;
    private ByteArrayOutputStream outputStream;

    @BeforeEach
    void setUp() {
        command = new ClientCommand();
        outputStream = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(outputStream);
        LogBuffer logBuffer = new LogBuffer();
        LogCapture logCapture = new LogCapture(logBuffer, ps, ps);
        ctx = spy(new CliContext(logBuffer, logCapture));
        // Create a ClientManager that references the spy, not the unwrapped object
        var mgr = new com.botwithus.bot.cli.ClientManager(ctx);
        doReturn(mgr).when(ctx).getClientManager();
    }

    private ParsedCommand parse(String input) {
        return CommandParser.parse(input);
    }

    private String output() {
        return outputStream.toString();
    }

    private Connection mockConnection(String name) {
        Connection conn = mock(Connection.class);
        when(conn.getName()).thenReturn(name);
        when(conn.isAlive()).thenReturn(true);
        ScriptRuntime runtime = mock(ScriptRuntime.class);
        when(runtime.getRunners()).thenReturn(List.of());
        when(conn.getRuntime()).thenReturn(runtime);
        return conn;
    }

    private ScriptRunner mockRunner(String scriptName, boolean running) {
        ScriptRunner runner = mock(ScriptRunner.class);
        when(runner.getScriptName()).thenReturn(scriptName);
        when(runner.isRunning()).thenReturn(running);
        when(runner.getManifest()).thenReturn(null);
        return runner;
    }

    // ── Metadata ─────────────────────────────────────────────────────────────

    @Test
    void commandNameAndAliases() {
        assertEquals("client", command.name());
        assertTrue(command.aliases().contains("cm"));
        assertTrue(command.aliases().contains("clients"));
    }

    @Test
    void noSubcommandShowsUsage() {
        command.execute(parse("client"), ctx);
        assertTrue(output().contains("Usage:"));
    }

    @Test
    void unknownSubcommand() {
        command.execute(parse("client foo"), ctx);
        assertTrue(output().contains("Unknown subcommand"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  client list
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class ListSubcommand {

        @Test
        void listNoConnections() {
            doReturn(List.of()).when(ctx).getConnections();
            command.execute(parse("client list"), ctx);
            assertTrue(output().contains("No connected clients"));
        }

        @Test
        void listShowsClients() {
            Connection c1 = mockConnection("Bot1");
            Connection c2 = mockConnection("Bot2");
            doReturn(List.of(c1, c2)).when(ctx).getConnections();

            command.execute(parse("client list"), ctx);
            String out = output();
            assertTrue(out.contains("Bot1"));
            assertTrue(out.contains("Bot2"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  client status
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class StatusSubcommand {

        @Test
        void statusAllNoScripts() {
            doReturn(List.of()).when(ctx).getConnections();
            command.execute(parse("client status"), ctx);
            assertTrue(output().contains("No scripts loaded"));
        }

        @Test
        void statusAllShowsScripts() {
            Connection c1 = mockConnection("Bot1");
            ScriptRunner r1 = mockRunner("Woodcutter", true);
            when(c1.getRuntime().getRunners()).thenReturn(List.of(r1));
            doReturn(List.of(c1)).when(ctx).getConnections();

            command.execute(parse("client status"), ctx);
            String out = output();
            assertTrue(out.contains("Bot1"));
            assertTrue(out.contains("Woodcutter"));
        }

        @Test
        void statusWithGroupFlag() {
            ctx.createGroup("skillers");
            ctx.getGroup("skillers").add("Bot1");

            Connection c1 = mockConnection("Bot1");
            ScriptRunner r1 = mockRunner("Woodcutter", true);
            when(c1.getRuntime().getRunners()).thenReturn(List.of(r1));
            doReturn(List.of(c1)).when(ctx).getGroupConnections("skillers");

            command.execute(parse("client status --group=skillers"), ctx);
            String out = output();
            assertTrue(out.contains("skillers"));
            assertTrue(out.contains("Woodcutter"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  client group
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class GroupSubcommands {

        @Test
        void groupNoSubcommandShowsUsage() {
            command.execute(parse("client group"), ctx);
            assertTrue(output().contains("Usage:"));
        }

        @Test
        void groupCreateWithDescription() {
            command.execute(parse("client group create skillers Skilling accounts"), ctx);
            String out = output();
            assertTrue(out.contains("created"));
            assertNotNull(ctx.getGroup("skillers"));
            assertEquals("Skilling accounts", ctx.getGroup("skillers").getDescription());
        }

        @Test
        void groupCreateWithoutDescription() {
            command.execute(parse("client group create combat"), ctx);
            assertTrue(output().contains("created"));
            assertNotNull(ctx.getGroup("combat"));
            assertNull(ctx.getGroup("combat").getDescription());
        }

        @Test
        void groupCreateAlreadyExists() {
            ctx.createGroup("skillers");
            command.execute(parse("client group create skillers"), ctx);
            assertTrue(output().contains("already exists"));
        }

        @Test
        void groupCreateMissingName() {
            command.execute(parse("client group create"), ctx);
            assertTrue(output().contains("Usage:"));
        }

        @Test
        void groupDelete() {
            ctx.createGroup("skillers");
            command.execute(parse("client group delete skillers"), ctx);
            assertTrue(output().contains("deleted"));
            assertNull(ctx.getGroup("skillers"));
        }

        @Test
        void groupDeleteNotFound() {
            command.execute(parse("client group delete nope"), ctx);
            assertTrue(output().contains("not found"));
        }

        @Test
        void groupDeleteMissingName() {
            command.execute(parse("client group delete"), ctx);
            assertTrue(output().contains("Usage:"));
        }

        @Test
        void groupAdd() {
            ctx.createGroup("skillers");
            command.execute(parse("client group add skillers Bot1"), ctx);
            assertTrue(output().contains("Added"));
            assertTrue(ctx.getGroup("skillers").contains("Bot1"));
        }

        @Test
        void groupAddGroupNotFound() {
            command.execute(parse("client group add nope Bot1"), ctx);
            assertTrue(output().contains("not found"));
        }

        @Test
        void groupAddMissingArgs() {
            command.execute(parse("client group add skillers"), ctx);
            assertTrue(output().contains("Usage:"));
        }

        @Test
        void groupRemove() {
            ctx.createGroup("skillers");
            ctx.getGroup("skillers").add("Bot1");
            command.execute(parse("client group remove skillers Bot1"), ctx);
            assertTrue(output().contains("Removed"));
            assertFalse(ctx.getGroup("skillers").contains("Bot1"));
        }

        @Test
        void groupRemoveGroupNotFound() {
            command.execute(parse("client group remove nope Bot1"), ctx);
            assertTrue(output().contains("not found"));
        }

        @Test
        void groupListEmpty() {
            command.execute(parse("client group list"), ctx);
            assertTrue(output().contains("No groups"));
        }

        @Test
        void groupListShowsGroups() {
            ctx.createGroup("skillers");
            ctx.getGroup("skillers").setDescription("Skilling bots");
            ctx.getGroup("skillers").add("Bot1");

            command.execute(parse("client group list"), ctx);
            String out = output();
            assertTrue(out.contains("skillers"));
            assertTrue(out.contains("Skilling bots"));
            assertTrue(out.contains("Bot1"));
        }

        @Test
        void groupInfo() {
            ctx.createGroup("combat");
            ctx.getGroup("combat").setDescription("Combat bots");
            ctx.getGroup("combat").add("Bot1");

            Connection c1 = mockConnection("Bot1");
            doReturn(List.of(c1)).when(ctx).getConnections();
            doReturn(List.of(c1)).when(ctx).getGroupConnections("combat");

            command.execute(parse("client group info combat"), ctx);
            String out = output();
            assertTrue(out.contains("combat"));
            assertTrue(out.contains("Combat bots"));
            assertTrue(out.contains("Bot1"));
        }

        @Test
        void groupInfoNotFound() {
            command.execute(parse("client group info nope"), ctx);
            assertTrue(output().contains("not found"));
        }

        @Test
        void groupInfoMissingName() {
            command.execute(parse("client group info"), ctx);
            assertTrue(output().contains("Usage:"));
        }

        @Test
        void groupInfoEmptyMembers() {
            ctx.createGroup("empty");
            command.execute(parse("client group info empty"), ctx);
            assertTrue(output().contains("no members"));
        }

        @Test
        void groupDescribe() {
            ctx.createGroup("skillers");
            command.execute(parse("client group describe skillers Best skilling group"), ctx);
            assertEquals("Best skilling group", ctx.getGroup("skillers").getDescription());
            assertTrue(output().contains("description set"));
        }

        @Test
        void groupDescribeGroupNotFound() {
            command.execute(parse("client group describe nope something"), ctx);
            assertTrue(output().contains("not found"));
        }

        @Test
        void groupDescribeMissingDesc() {
            ctx.createGroup("skillers");
            command.execute(parse("client group describe skillers"), ctx);
            assertTrue(output().contains("Usage:"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  client start/stop/restart
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class ScriptActions {

        @Test
        void startMissingScriptName() {
            command.execute(parse("client start"), ctx);
            assertTrue(output().contains("Usage:"));
        }

        @Test
        void startMissingTarget() {
            command.execute(parse("client start Woodcutter"), ctx);
            assertTrue(output().contains("Specify target"));
        }

        @Test
        void startOnSpecificClient() {
            Connection c1 = mockConnection("Bot1");
            doReturn(List.of(c1)).when(ctx).getConnections();

            ScriptRunner runner = mockRunner("Woodcutter", false);
            when(c1.getRuntime().findRunner("Woodcutter")).thenReturn(runner);

            command.execute(parse("client start Woodcutter --on=Bot1"), ctx);
            verify(runner).start();
            String out = output();
            assertTrue(out.contains("Bot1"));
            assertTrue(out.contains("started"));
        }

        @Test
        void startOnGroup() {
            ctx.createGroup("skillers");
            ctx.getGroup("skillers").add("Bot1");

            Connection c1 = mockConnection("Bot1");
            doReturn(List.of(c1)).when(ctx).getConnections();
            doReturn(List.of(c1)).when(ctx).getGroupConnections("skillers");

            ScriptRunner runner = mockRunner("Woodcutter", false);
            when(c1.getRuntime().findRunner("Woodcutter")).thenReturn(runner);

            command.execute(parse("client start Woodcutter --group=skillers"), ctx);
            verify(runner).start();
            assertTrue(output().contains("started"));
        }

        @Test
        void startOnAll() {
            Connection c1 = mockConnection("Bot1");
            Connection c2 = mockConnection("Bot2");
            doReturn(List.of(c1, c2)).when(ctx).getConnections();

            ScriptRunner r1 = mockRunner("Woodcutter", false);
            ScriptRunner r2 = mockRunner("Woodcutter", false);
            when(c1.getRuntime().findRunner("Woodcutter")).thenReturn(r1);
            when(c2.getRuntime().findRunner("Woodcutter")).thenReturn(r2);

            command.execute(parse("client start Woodcutter --all"), ctx);
            verify(r1).start();
            verify(r2).start();
        }

        @Test
        void stopOnSpecificClient() {
            Connection c1 = mockConnection("Bot1");
            doReturn(List.of(c1)).when(ctx).getConnections();
            when(c1.getRuntime().stopScript("Woodcutter")).thenReturn(true);

            command.execute(parse("client stop Woodcutter --on=Bot1"), ctx);
            assertTrue(output().contains("stopped"));
        }

        @Test
        void restartOnSpecificClient() {
            Connection c1 = mockConnection("Bot1");
            doReturn(List.of(c1)).when(ctx).getConnections();

            ScriptRunner runner = mockRunner("Woodcutter", true);
            when(c1.getRuntime().findRunner("Woodcutter")).thenReturn(runner);

            command.execute(parse("client restart Woodcutter --on=Bot1"), ctx);
            verify(runner).stop();
            verify(runner).start();
            assertTrue(output().contains("restarted"));
        }

        @Test
        void startScriptNotFound() {
            Connection c1 = mockConnection("Bot1");
            doReturn(List.of(c1)).when(ctx).getConnections();
            when(c1.getRuntime().findRunner("Nope")).thenReturn(null);

            command.execute(parse("client start Nope --on=Bot1"), ctx);
            assertTrue(output().contains("script not found"));
        }

        @Test
        void startClientNotFound() {
            doReturn(List.of()).when(ctx).getConnections();
            command.execute(parse("client start Woodcutter --on=Nope"), ctx);
            assertTrue(output().contains("client not found"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  client stopall
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class StopAllSubcommand {

        @Test
        void stopallMissingTarget() {
            command.execute(parse("client stopall"), ctx);
            assertTrue(output().contains("Specify target"));
        }

        @Test
        void stopallOnGroup() {
            ctx.createGroup("farm");
            ctx.getGroup("farm").add("Bot1");

            Connection c1 = mockConnection("Bot1");
            doReturn(List.of(c1)).when(ctx).getGroupConnections("farm");

            command.execute(parse("client stopall --group=farm"), ctx);
            verify(c1.getRuntime()).stopAll();
        }

        @Test
        void stopallOnAll() {
            Connection c1 = mockConnection("Bot1");
            Connection c2 = mockConnection("Bot2");
            doReturn(List.of(c1, c2)).when(ctx).getConnections();

            command.execute(parse("client stopall --all"), ctx);
            assertTrue(output().contains("Stopped all scripts on all clients"));
        }
    }
}
