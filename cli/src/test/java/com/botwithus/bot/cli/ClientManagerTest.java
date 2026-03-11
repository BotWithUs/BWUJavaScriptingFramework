package com.botwithus.bot.cli;

import com.botwithus.bot.cli.ClientManager.ClientScriptStatus;
import com.botwithus.bot.cli.ClientManager.ScriptOpResult;
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

class ClientManagerTest {

    private CliContext ctx;
    private ClientManager mgr;

    @BeforeEach
    void setUp() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(out);
        LogBuffer logBuffer = new LogBuffer();
        LogCapture logCapture = new LogCapture(logBuffer, ps, ps);
        ctx = spy(new CliContext(logBuffer, logCapture));
        // Create a ClientManager that references the spy, not the real object
        mgr = new ClientManager(ctx);
        doReturn(mgr).when(ctx).getClientManager();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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

    private void stubConnections(Connection... conns) {
        doReturn(List.of(conns)).when(ctx).getConnections();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Client queries
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class ClientQueries {

        @Test
        void getClientsReturnsAllConnections() {
            Connection c1 = mockConnection("Bot1");
            Connection c2 = mockConnection("Bot2");
            stubConnections(c1, c2);

            assertEquals(2, mgr.getClients().size());
        }

        @Test
        void getClientsEmptyWhenNoConnections() {
            stubConnections();
            assertTrue(mgr.getClients().isEmpty());
        }

        @Test
        void getClientByName() {
            Connection c1 = mockConnection("Bot1");
            stubConnections(c1);

            assertSame(c1, mgr.getClient("Bot1"));
        }

        @Test
        void getClientReturnsNullForUnknown() {
            stubConnections();
            assertNull(mgr.getClient("Nope"));
        }

        @Test
        void getClientNames() {
            Connection c1 = mockConnection("Bot1");
            Connection c2 = mockConnection("Bot2");
            stubConnections(c1, c2);

            List<String> names = mgr.getClientNames();
            assertEquals(2, names.size());
            assertTrue(names.contains("Bot1"));
            assertTrue(names.contains("Bot2"));
        }

        @Test
        void isClientAliveReturnsTrue() {
            Connection c1 = mockConnection("Bot1");
            stubConnections(c1);

            assertTrue(mgr.isClientAlive("Bot1"));
        }

        @Test
        void isClientAliveReturnsFalseWhenDisconnected() {
            Connection c1 = mockConnection("Bot1");
            when(c1.isAlive()).thenReturn(false);
            stubConnections(c1);

            assertFalse(mgr.isClientAlive("Bot1"));
        }

        @Test
        void isClientAliveReturnsFalseForUnknown() {
            stubConnections();
            assertFalse(mgr.isClientAlive("Nope"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Group management
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class GroupManagement {

        @Test
        void createGroupWithDescription() {
            ConnectionGroup group = mgr.createGroup("skillers", "Skilling accounts");
            assertNotNull(group);
            assertEquals("skillers", group.getName());
            assertEquals("Skilling accounts", group.getDescription());
        }

        @Test
        void createGroupWithoutDescription() {
            ConnectionGroup group = mgr.createGroup("combat");
            assertNotNull(group);
            assertNull(group.getDescription());
        }

        @Test
        void createGroupReturnsSameIfExists() {
            mgr.createGroup("skillers", "desc1");
            ConnectionGroup second = mgr.createGroup("skillers", "desc2");
            // Should return existing group, not overwrite
            assertEquals("desc1", second.getDescription());
        }

        @Test
        void deleteGroup() {
            mgr.createGroup("skillers");
            assertTrue(mgr.deleteGroup("skillers"));
            assertNull(mgr.getGroup("skillers"));
        }

        @Test
        void deleteNonExistentGroupReturnsFalse() {
            assertFalse(mgr.deleteGroup("nope"));
        }

        @Test
        void getGroupReturnsNull() {
            assertNull(mgr.getGroup("nope"));
        }

        @Test
        void getGroupsReturnsAll() {
            mgr.createGroup("skillers");
            mgr.createGroup("combat");
            assertEquals(2, mgr.getGroups().size());
        }

        @Test
        void addToGroup() {
            mgr.createGroup("skillers");
            assertTrue(mgr.addToGroup("skillers", "Bot1"));

            ConnectionGroup group = mgr.getGroup("skillers");
            assertTrue(group.contains("Bot1"));
        }

        @Test
        void addToGroupReturnsFalseForUnknownGroup() {
            assertFalse(mgr.addToGroup("nope", "Bot1"));
        }

        @Test
        void removeFromGroup() {
            mgr.createGroup("skillers");
            mgr.addToGroup("skillers", "Bot1");
            assertTrue(mgr.removeFromGroup("skillers", "Bot1"));
            assertFalse(mgr.getGroup("skillers").contains("Bot1"));
        }

        @Test
        void removeFromGroupReturnsFalseForUnknownGroup() {
            assertFalse(mgr.removeFromGroup("nope", "Bot1"));
        }

        @Test
        void getGroupClientsReturnsOnlyAlive() {
            mgr.createGroup("farm");
            mgr.addToGroup("farm", "Bot1");
            mgr.addToGroup("farm", "Bot2");

            Connection c1 = mockConnection("Bot1");
            doReturn(List.of(c1)).when(ctx).getGroupConnections("farm");

            List<Connection> clients = mgr.getGroupClients("farm");
            assertEquals(1, clients.size());
            assertEquals("Bot1", clients.get(0).getName());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Single-client script operations
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class SingleClientScriptOps {

        @Test
        void startScriptSuccess() {
            Connection conn = mockConnection("Bot1");
            stubConnections(conn);

            ScriptRunner runner = mockRunner("Woodcutter", false);
            when(conn.getRuntime().findRunner("Woodcutter")).thenReturn(runner);

            ScriptOpResult r = mgr.startScript("Bot1", "Woodcutter");
            assertTrue(r.success());
            assertEquals("Bot1", r.clientName());
            assertEquals("Woodcutter", r.scriptName());
            assertEquals("started", r.message());
            verify(runner).start();
        }

        @Test
        void startScriptClientNotFound() {
            stubConnections();
            ScriptOpResult r = mgr.startScript("Nope", "Woodcutter");
            assertFalse(r.success());
            assertEquals("client not found", r.message());
        }

        @Test
        void startScriptClientDisconnected() {
            Connection conn = mockConnection("Bot1");
            when(conn.isAlive()).thenReturn(false);
            stubConnections(conn);

            ScriptOpResult r = mgr.startScript("Bot1", "Woodcutter");
            assertFalse(r.success());
            assertEquals("client disconnected", r.message());
        }

        @Test
        void startScriptNotFound() {
            Connection conn = mockConnection("Bot1");
            stubConnections(conn);
            when(conn.getRuntime().findRunner("Nope")).thenReturn(null);

            ScriptOpResult r = mgr.startScript("Bot1", "Nope");
            assertFalse(r.success());
            assertEquals("script not found", r.message());
        }

        @Test
        void startScriptAlreadyRunning() {
            Connection conn = mockConnection("Bot1");
            stubConnections(conn);

            ScriptRunner runner = mockRunner("Woodcutter", true);
            when(conn.getRuntime().findRunner("Woodcutter")).thenReturn(runner);

            ScriptOpResult r = mgr.startScript("Bot1", "Woodcutter");
            assertFalse(r.success());
            assertEquals("already running", r.message());
            verify(runner, never()).start();
        }

        @Test
        void stopScriptSuccess() {
            Connection conn = mockConnection("Bot1");
            stubConnections(conn);
            when(conn.getRuntime().stopScript("Woodcutter")).thenReturn(true);

            ScriptOpResult r = mgr.stopScript("Bot1", "Woodcutter");
            assertTrue(r.success());
            assertEquals("stopped", r.message());
        }

        @Test
        void stopScriptNotFound() {
            Connection conn = mockConnection("Bot1");
            stubConnections(conn);
            when(conn.getRuntime().stopScript("Nope")).thenReturn(false);

            ScriptOpResult r = mgr.stopScript("Bot1", "Nope");
            assertFalse(r.success());
            assertEquals("script not found", r.message());
        }

        @Test
        void stopScriptClientNotFound() {
            stubConnections();
            ScriptOpResult r = mgr.stopScript("Nope", "Woodcutter");
            assertFalse(r.success());
            assertEquals("client not found", r.message());
        }

        @Test
        void restartScriptNotRunning() {
            Connection conn = mockConnection("Bot1");
            stubConnections(conn);

            ScriptRunner runner = mockRunner("Woodcutter", false);
            when(conn.getRuntime().findRunner("Woodcutter")).thenReturn(runner);

            ScriptOpResult r = mgr.restartScript("Bot1", "Woodcutter");
            assertTrue(r.success());
            assertEquals("restarted", r.message());
            verify(runner, never()).stop();
            verify(runner).start();
        }

        @Test
        void restartScriptRunning() {
            Connection conn = mockConnection("Bot1");
            stubConnections(conn);

            ScriptRunner runner = mockRunner("Woodcutter", true);
            when(conn.getRuntime().findRunner("Woodcutter")).thenReturn(runner);

            ScriptOpResult r = mgr.restartScript("Bot1", "Woodcutter");
            assertTrue(r.success());
            verify(runner).stop();
            verify(runner).awaitStop(2000);
            verify(runner).start();
        }

        @Test
        void restartScriptClientNotFound() {
            stubConnections();
            ScriptOpResult r = mgr.restartScript("Nope", "Woodcutter");
            assertFalse(r.success());
        }

        @Test
        void restartScriptScriptNotFound() {
            Connection conn = mockConnection("Bot1");
            stubConnections(conn);
            when(conn.getRuntime().findRunner("Nope")).thenReturn(null);

            ScriptOpResult r = mgr.restartScript("Bot1", "Nope");
            assertFalse(r.success());
            assertEquals("script not found", r.message());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Group script operations
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class GroupScriptOps {

        @Test
        void startScriptOnGroupSuccess() {
            mgr.createGroup("skillers", "Skilling bots");
            mgr.addToGroup("skillers", "Bot1");
            mgr.addToGroup("skillers", "Bot2");

            Connection c1 = mockConnection("Bot1");
            Connection c2 = mockConnection("Bot2");
            stubConnections(c1, c2);

            ScriptRunner r1 = mockRunner("Woodcutter", false);
            ScriptRunner r2 = mockRunner("Woodcutter", false);
            when(c1.getRuntime().findRunner("Woodcutter")).thenReturn(r1);
            when(c2.getRuntime().findRunner("Woodcutter")).thenReturn(r2);

            doReturn(List.of(c1, c2)).when(ctx).getGroupConnections("skillers");

            List<ScriptOpResult> results = mgr.startScriptOnGroup("skillers", "Woodcutter");
            assertEquals(2, results.stream().filter(ScriptOpResult::success).count());
            verify(r1).start();
            verify(r2).start();
        }

        @Test
        void startScriptOnGroupNotFound() {
            List<ScriptOpResult> results = mgr.startScriptOnGroup("nope", "Woodcutter");
            assertEquals(1, results.size());
            assertFalse(results.get(0).success());
            assertEquals("group not found", results.get(0).message());
        }

        @Test
        void startScriptOnGroupNoActiveClients() {
            mgr.createGroup("empty");
            doReturn(List.of()).when(ctx).getGroupConnections("empty");

            List<ScriptOpResult> results = mgr.startScriptOnGroup("empty", "Woodcutter");
            assertEquals(1, results.size());
            assertFalse(results.get(0).success());
            assertTrue(results.get(0).message().contains("no active clients"));
        }

        @Test
        void startScriptOnGroupWarnsDisconnected() {
            mgr.createGroup("mixed");
            mgr.addToGroup("mixed", "Bot1");
            mgr.addToGroup("mixed", "Bot2");

            Connection c1 = mockConnection("Bot1");
            stubConnections(c1);

            ScriptRunner runner = mockRunner("Woodcutter", false);
            when(c1.getRuntime().findRunner("Woodcutter")).thenReturn(runner);

            // Only Bot1 is active
            doReturn(List.of(c1)).when(ctx).getGroupConnections("mixed");

            List<ScriptOpResult> results = mgr.startScriptOnGroup("mixed", "Woodcutter");
            // Should have success for Bot1 + disconnected warning for Bot2
            assertTrue(results.stream().anyMatch(r -> r.success() && r.clientName().equals("Bot1")));
            assertTrue(results.stream().anyMatch(r -> !r.success() && r.clientName().equals("Bot2")
                    && r.message().equals("client disconnected")));
        }

        @Test
        void stopScriptOnGroup() {
            mgr.createGroup("combat", "Combat bots");
            mgr.addToGroup("combat", "Bot1");

            Connection c1 = mockConnection("Bot1");
            stubConnections(c1);
            when(c1.getRuntime().stopScript("Fighter")).thenReturn(true);
            doReturn(List.of(c1)).when(ctx).getGroupConnections("combat");

            List<ScriptOpResult> results = mgr.stopScriptOnGroup("combat", "Fighter");
            assertEquals(1, results.stream().filter(ScriptOpResult::success).count());
        }

        @Test
        void restartScriptOnGroup() {
            mgr.createGroup("combat");
            mgr.addToGroup("combat", "Bot1");

            Connection c1 = mockConnection("Bot1");
            stubConnections(c1);

            ScriptRunner runner = mockRunner("Fighter", true);
            when(c1.getRuntime().findRunner("Fighter")).thenReturn(runner);
            doReturn(List.of(c1)).when(ctx).getGroupConnections("combat");

            List<ScriptOpResult> results = mgr.restartScriptOnGroup("combat", "Fighter");
            assertTrue(results.stream().allMatch(ScriptOpResult::success));
            verify(runner).stop();
            verify(runner).start();
        }

        @Test
        void stopAllScriptsOnGroup() {
            mgr.createGroup("farm");
            mgr.addToGroup("farm", "Bot1");

            Connection c1 = mockConnection("Bot1");
            stubConnections(c1);
            doReturn(List.of(c1)).when(ctx).getGroupConnections("farm");

            List<ScriptOpResult> results = mgr.stopAllScriptsOnGroup("farm");
            assertTrue(results.stream().anyMatch(r -> r.success()));
            verify(c1.getRuntime()).stopAll();
        }

        @Test
        void stopAllScriptsOnGroupNotFound() {
            List<ScriptOpResult> results = mgr.stopAllScriptsOnGroup("nope");
            assertEquals(1, results.size());
            assertFalse(results.get(0).success());
            assertEquals("group not found", results.get(0).message());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  All-client script operations
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class AllClientScriptOps {

        @Test
        void startScriptOnAll() {
            Connection c1 = mockConnection("Bot1");
            Connection c2 = mockConnection("Bot2");
            stubConnections(c1, c2);

            ScriptRunner r1 = mockRunner("Woodcutter", false);
            ScriptRunner r2 = mockRunner("Woodcutter", false);
            when(c1.getRuntime().findRunner("Woodcutter")).thenReturn(r1);
            when(c2.getRuntime().findRunner("Woodcutter")).thenReturn(r2);

            List<ScriptOpResult> results = mgr.startScriptOnAll("Woodcutter");
            assertEquals(2, results.size());
            assertTrue(results.stream().allMatch(ScriptOpResult::success));
            verify(r1).start();
            verify(r2).start();
        }

        @Test
        void startScriptOnAllSkipsDisconnected() {
            Connection c1 = mockConnection("Bot1");
            Connection c2 = mockConnection("Bot2");
            when(c2.isAlive()).thenReturn(false);
            stubConnections(c1, c2);

            ScriptRunner r1 = mockRunner("Woodcutter", false);
            when(c1.getRuntime().findRunner("Woodcutter")).thenReturn(r1);

            List<ScriptOpResult> results = mgr.startScriptOnAll("Woodcutter");
            assertEquals(1, results.size());
            assertTrue(results.get(0).success());
            assertEquals("Bot1", results.get(0).clientName());
        }

        @Test
        void stopScriptOnAll() {
            Connection c1 = mockConnection("Bot1");
            Connection c2 = mockConnection("Bot2");
            stubConnections(c1, c2);
            when(c1.getRuntime().stopScript("Woodcutter")).thenReturn(true);
            when(c2.getRuntime().stopScript("Woodcutter")).thenReturn(true);

            List<ScriptOpResult> results = mgr.stopScriptOnAll("Woodcutter");
            assertEquals(2, results.size());
            assertTrue(results.stream().allMatch(ScriptOpResult::success));
        }

        @Test
        void restartScriptOnAll() {
            Connection c1 = mockConnection("Bot1");
            stubConnections(c1);

            ScriptRunner runner = mockRunner("Woodcutter", true);
            when(c1.getRuntime().findRunner("Woodcutter")).thenReturn(runner);

            List<ScriptOpResult> results = mgr.restartScriptOnAll("Woodcutter");
            assertEquals(1, results.size());
            assertTrue(results.get(0).success());
            verify(runner).stop();
            verify(runner).start();
        }

        @Test
        void stopAllScriptsOnAll() {
            Connection c1 = mockConnection("Bot1");
            Connection c2 = mockConnection("Bot2");
            stubConnections(c1, c2);

            mgr.stopAllScriptsOnAll();
            verify(c1.getRuntime()).stopAll();
            verify(c2.getRuntime()).stopAll();
        }

        @Test
        void stopAllScriptsOnAllSkipsDisconnected() {
            Connection c1 = mockConnection("Bot1");
            Connection c2 = mockConnection("Bot2");
            when(c2.isAlive()).thenReturn(false);
            stubConnections(c1, c2);

            mgr.stopAllScriptsOnAll();
            verify(c1.getRuntime()).stopAll();
            verify(c2.getRuntime(), never()).stopAll();
        }

        @Test
        void startOnAllWithNoClients() {
            stubConnections();
            List<ScriptOpResult> results = mgr.startScriptOnAll("Woodcutter");
            assertTrue(results.isEmpty());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Status queries
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class StatusQueries {

        @Test
        void getStatusAllReturnsAllScripts() {
            Connection c1 = mockConnection("Bot1");
            Connection c2 = mockConnection("Bot2");

            ScriptRunner r1 = mockRunner("Woodcutter", true);
            ScriptRunner r2 = mockRunner("Fighter", false);
            when(c1.getRuntime().getRunners()).thenReturn(List.of(r1));
            when(c2.getRuntime().getRunners()).thenReturn(List.of(r2));
            stubConnections(c1, c2);

            List<ClientScriptStatus> statuses = mgr.getStatusAll();
            assertEquals(2, statuses.size());

            ClientScriptStatus s1 = statuses.get(0);
            assertEquals("Bot1", s1.clientName());
            assertEquals("Woodcutter", s1.scriptName());
            assertTrue(s1.running());
            assertTrue(s1.clientAlive());

            ClientScriptStatus s2 = statuses.get(1);
            assertEquals("Bot2", s2.clientName());
            assertEquals("Fighter", s2.scriptName());
            assertFalse(s2.running());
        }

        @Test
        void getStatusAllEmptyWhenNoScripts() {
            Connection c1 = mockConnection("Bot1");
            stubConnections(c1);

            List<ClientScriptStatus> statuses = mgr.getStatusAll();
            assertTrue(statuses.isEmpty());
        }

        @Test
        void getStatusForGroup() {
            mgr.createGroup("skillers");
            mgr.addToGroup("skillers", "Bot1");

            Connection c1 = mockConnection("Bot1");
            ScriptRunner r1 = mockRunner("Woodcutter", true);
            when(c1.getRuntime().getRunners()).thenReturn(List.of(r1));
            doReturn(List.of(c1)).when(ctx).getGroupConnections("skillers");

            List<ClientScriptStatus> statuses = mgr.getStatusForGroup("skillers");
            assertEquals(1, statuses.size());
            assertEquals("Bot1", statuses.get(0).clientName());
            assertEquals("Woodcutter", statuses.get(0).scriptName());
            assertTrue(statuses.get(0).running());
        }

        @Test
        void getStatusForGroupEmptyWhenGroupNotFound() {
            List<ClientScriptStatus> statuses = mgr.getStatusForGroup("nope");
            assertTrue(statuses.isEmpty());
        }

        @Test
        void statusVersionFallsBackToQuestionMark() {
            Connection c1 = mockConnection("Bot1");
            ScriptRunner r1 = mockRunner("Woodcutter", false);
            when(r1.getManifest()).thenReturn(null);
            when(c1.getRuntime().getRunners()).thenReturn(List.of(r1));
            stubConnections(c1);

            List<ClientScriptStatus> statuses = mgr.getStatusAll();
            assertEquals("?", statuses.get(0).version());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ScriptOpResult record
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class ScriptOpResultTests {

        @Test
        void okResult() {
            ScriptOpResult r = ScriptOpResult.ok("Bot1", "Woodcutter", "started");
            assertTrue(r.success());
            assertEquals("Bot1", r.clientName());
            assertEquals("Woodcutter", r.scriptName());
            assertEquals("started", r.message());
        }

        @Test
        void clientNotFoundResult() {
            ScriptOpResult r = ScriptOpResult.clientNotFound("Bot1");
            assertFalse(r.success());
            assertNull(r.scriptName());
        }

        @Test
        void clientDisconnectedResult() {
            ScriptOpResult r = ScriptOpResult.clientDisconnected("Bot1");
            assertFalse(r.success());
            assertEquals("client disconnected", r.message());
        }

        @Test
        void scriptNotFoundResult() {
            ScriptOpResult r = ScriptOpResult.scriptNotFound("Bot1", "Nope");
            assertFalse(r.success());
            assertEquals("Nope", r.scriptName());
        }

        @Test
        void alreadyRunningResult() {
            ScriptOpResult r = ScriptOpResult.alreadyRunning("Bot1", "Woodcutter");
            assertFalse(r.success());
            assertEquals("already running", r.message());
        }

        @Test
        void groupNotFoundResult() {
            ScriptOpResult r = ScriptOpResult.groupNotFound("team");
            assertFalse(r.success());
            assertEquals("group not found", r.message());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ClientScriptStatus record
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class ClientScriptStatusTests {

        @Test
        void recordFields() {
            ClientScriptStatus s = new ClientScriptStatus("Bot1", "Woodcutter", "1.0", true, true);
            assertEquals("Bot1", s.clientName());
            assertEquals("Woodcutter", s.scriptName());
            assertEquals("1.0", s.version());
            assertTrue(s.running());
            assertTrue(s.clientAlive());
        }
    }
}
