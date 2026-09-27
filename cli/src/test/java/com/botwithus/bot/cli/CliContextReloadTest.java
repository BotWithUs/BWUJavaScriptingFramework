package com.botwithus.bot.cli;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.cli.command.CommandParser;
import com.botwithus.bot.cli.command.impl.ReloadCommand;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogCapture;
import com.botwithus.bot.cli.scripts.AfterReload;
import com.botwithus.bot.cli.scripts.ReloadSummary;
import com.botwithus.bot.cli.scripts.RunningPair;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SettingKeys;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Reload through {@link CliContext#reloadScripts} and the {@code reload} command,
 * over real {@link ScriptRuntime}s running real scripts. Only the connection
 * (a pipe) and the JAR load are stood in for.
 */
class CliContextReloadTest {

    private static final long LOOP_DELAY_MS = 20L;

    @TempDir
    Path tmp;

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final List<ScriptRuntime> runtimes = new ArrayList<>();
    private CliContext ctx;
    private HostSettings settings;

    abstract static class LoopingScript implements BotScript {
        @Override public void onStart(ScriptContext context) { }
        @Override public int onLoop() { return (int) LOOP_DELAY_MS; }
        @Override public void onStop() { }
    }

    @ScriptManifest(name = "Alpha")
    static final class Alpha extends LoopingScript { }

    @ScriptManifest(name = "Beta")
    static final class Beta extends LoopingScript { }

    @BeforeEach
    void setUp() {
        PrintStream ps = new PrintStream(output, true, StandardCharsets.UTF_8);
        LogBuffer logBuffer = new LogBuffer();
        ctx = spy(new CliContext(logBuffer, new LogCapture(logBuffer, ps, ps), tmp.resolve("groups.json")));
        settings = HostSettings.open(tmp.resolve("settings"));
        ctx.setSettings(settings);
    }

    @AfterEach
    void tearDown() {
        runtimes.forEach(ScriptRuntime::stopAll);
        settings.close();
    }

    private Connection client(String name) {
        ScriptRuntime runtime = new ScriptRuntime(mock(ScriptContext.class));
        runtimes.add(runtime);
        Connection conn = mock(Connection.class);
        when(conn.getName()).thenReturn(name);
        when(conn.isAlive()).thenReturn(true);
        when(conn.getRuntime()).thenReturn(runtime);
        return conn;
    }

    /** Each load pass yields fresh instances, as a real one does. */
    private void jarsYield(Supplier<List<BotScript>> scripts) {
        doAnswer(invocation -> scripts.get()).when(ctx).loadScripts();
    }

    private static void run(Connection conn, BotScript... scripts) {
        for (BotScript script : scripts) {
            conn.getRuntime().registerScript(script);
        }
    }

    private static void start(Connection conn, String scriptName) {
        conn.getRuntime().findRunner(scriptName).start();
    }

    private static Set<String> runningOn(Connection conn) {
        return conn.getRuntime().getRunners().stream()
                .filter(ScriptRunner::isRunning)
                .map(ScriptRunner::getScriptName)
                .collect(Collectors.toSet());
    }

    /** Bot1 runs Alpha (Beta idle); Bot2 runs Beta (Alpha idle). */
    private List<Connection> twoClientsRunningDifferentScripts() {
        Connection bot1 = client("Bot1");
        Connection bot2 = client("Bot2");
        run(bot1, new Alpha(), new Beta());
        run(bot2, new Alpha(), new Beta());
        start(bot1, "Alpha");
        start(bot2, "Beta");
        return List.of(bot1, bot2);
    }

    @Test
    void restartAfterReload_restartsExactlyThePairsThatWereRunning() {
        settings.set(SettingKeys.RESTART_AFTER_RELOAD, true);
        List<Connection> clients = twoClientsRunningDifferentScripts();
        BotScript oldAlphaOnBot1 = clients.get(0).getRuntime().findRunner("Alpha").getScript();
        jarsYield(() -> List.of(new Alpha(), new Beta()));

        ReloadSummary summary = ctx.reloadScripts(clients, ctx.afterReloadSetting());

        assertAll(
                () -> assertEquals(Set.of("Alpha"), runningOn(clients.get(0))),
                () -> assertEquals(Set.of("Beta"), runningOn(clients.get(1))),
                () -> assertNotSame(oldAlphaOnBot1, clients.get(0).getRuntime().findRunner("Alpha").getScript(),
                        "the reloaded instance runs, not the old one"),
                () -> assertEquals(List.of(new RunningPair("Bot1", "Alpha"), new RunningPair("Bot2", "Beta")),
                        summary.restarted()),
                () -> assertTrue(summary.missing().isEmpty()));
    }

    @Test
    void restartAfterReload_reportsARunningScriptThatNoLongerExists() {
        settings.set(SettingKeys.RESTART_AFTER_RELOAD, true);
        List<Connection> clients = twoClientsRunningDifferentScripts();
        jarsYield(() -> List.of(new Alpha()));

        ReloadSummary summary = ctx.reloadScripts(clients, ctx.afterReloadSetting());

        assertAll(
                () -> assertEquals(Set.of("Alpha"), runningOn(clients.get(0))),
                () -> assertEquals(Set.of(), runningOn(clients.get(1))),
                () -> assertEquals(List.of(new RunningPair("Bot2", "Beta")), summary.missing()));
    }

    @Test
    void withTheSettingOff_aReloadStartsNothing() {
        List<Connection> clients = twoClientsRunningDifferentScripts();
        jarsYield(() -> List.of(new Alpha(), new Beta()));

        ReloadSummary summary = ctx.reloadScripts(clients, ctx.afterReloadSetting());

        assertAll(
                () -> assertEquals(AfterReload.REGISTER_ONLY, summary.after()),
                () -> assertEquals(Set.of(), runningOn(clients.get(0))),
                () -> assertEquals(Set.of(), runningOn(clients.get(1))),
                () -> assertEquals(2, clients.get(0).getRuntime().getRunners().size(), "registered, not started"));
    }

    @Test
    void theReloadCommand_restartsPairsAcrossAGroup_andNamesTheMissingOne() {
        settings.set(SettingKeys.RESTART_AFTER_RELOAD, true);
        List<Connection> clients = twoClientsRunningDifferentScripts();
        ctx.createGroup("farm");
        ctx.getGroup("farm").add("Bot1");
        ctx.getGroup("farm").add("Bot2");
        doReturn(clients).when(ctx).getGroupConnections("farm");
        jarsYield(() -> List.of(new Alpha()));

        new ReloadCommand().execute(CommandParser.parse("reload --group=farm"), ctx);

        String out = output.toString(StandardCharsets.UTF_8);
        assertAll(
                () -> assertEquals(Set.of("Alpha"), runningOn(clients.get(0))),
                () -> assertEquals(Set.of(), runningOn(clients.get(1))),
                () -> assertTrue(out.contains("[Bot1] Restarted Alpha."), out),
                () -> assertTrue(out.contains("[Bot2] Not restarted: Beta"), out));
    }

    @Test
    void reloadStart_startsEveryScriptOnEveryClient_whateverWasRunning() {
        List<Connection> clients = twoClientsRunningDifferentScripts();
        ctx.createGroup("farm");
        ctx.getGroup("farm").add("Bot1");
        ctx.getGroup("farm").add("Bot2");
        doReturn(clients).when(ctx).getGroupConnections("farm");
        jarsYield(() -> List.of(new Alpha(), new Beta()));

        new ReloadCommand().execute(CommandParser.parse("reload --group=farm --start"), ctx);

        assertEquals(Set.of("Alpha", "Beta"), runningOn(clients.get(0)));
        assertEquals(Set.of("Alpha", "Beta"), runningOn(clients.get(1)));
    }

    @Test
    void reloadingNoClients_stillRunsALoadPass() {
        doReturn(List.of()).when(ctx).getConnections();
        List<Integer> passes = new ArrayList<>();
        jarsYield(() -> {
            passes.add(1);
            return List.of(new Alpha());
        });

        ReloadSummary summary = ctx.reloadAllScripts(AfterReload.REGISTER_ONLY);

        assertEquals(1, passes.size(), "the failed-load list must still refresh");
        assertTrue(summary.clients().isEmpty());
    }
}
