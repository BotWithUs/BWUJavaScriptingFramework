package com.botwithus.bot.cli;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.script.ClientOrchestrator.OpResult;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.groups.QueuedStart;
import com.botwithus.bot.core.runtime.ScriptRuntime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.botwithus.bot.cli.TestContexts.connectIdentified;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The start-when-back queue wired into the real {@link CliContext}: a start
 * queued for a client that is not connected happens when the host identifies
 * it, through the status tracker, and a stop cancels it. Only the agents are
 * fakes; the scripts are trivial.
 */
class CliContextStartWhenBackTest {

    private static final String UUID_A = "0123456789abcdef0123456789abcdef";
    private static final String UUID_B = "fedcba9876543210fedcba9876543210";
    private static final String PIPE_A = "BotWithUs_1001";
    private static final String PIPE_B = "BotWithUs_2002";
    private static final String WOODCUTTER = "Woodcutter";
    private static final int LOOP_MS = 50;

    @ScriptManifest(name = WOODCUTTER, version = "1.0", author = "test")
    public static final class Woodcutter implements BotScript {
        @Override public void onStart(ScriptContext ctx) { }
        @Override public int onLoop() { return LOOP_MS; }
        @Override public void onStop() { }
    }

    @TempDir
    Path dir;

    private final PrintStream discard = new PrintStream(OutputStream.nullOutputStream());
    private final List<ScriptRuntime> runtimes = new ArrayList<>();

    @AfterEach
    void stopScripts() {
        runtimes.forEach(ScriptRuntime::stopAll);
    }

    private CliContext newContext() {
        return TestContexts.inDirInLine(dir, discard);
    }

    /** A client's runtime with the woodcutter installed but not running. */
    private ScriptRuntime runtimeWithWoodcutter(String pipe) {
        ScriptRuntime runtime = TestContexts.runtime(pipe);
        runtime.registerScript(new Woodcutter());
        runtimes.add(runtime);
        return runtime;
    }

    private static boolean isRunning(ScriptRuntime runtime) {
        return runtime.findRunner(WOODCUTTER).isRunning();
    }

    @Test
    void aQueuedStart_happensWhenTheClientIsIdentified_andLeavesTheQueue() {
        CliContext ctx = newContext();
        assertTrue(ctx.startWhenBack(UUID_A, WOODCUTTER));
        ScriptRuntime runtime = runtimeWithWoodcutter(PIPE_A);

        connectIdentified(ctx, PIPE_A, UUID_A, "Alpha", runtime);

        assertTrue(isRunning(runtime));
        assertTrue(ctx.getStartWhenBackQueue().all().isEmpty());
    }

    @Test
    void aQueuedStart_survivesAHostRestart() {
        newContext().startWhenBack(UUID_A, WOODCUTTER);
        CliContext restarted = newContext();
        restarted.loadGroups();
        ScriptRuntime runtime = runtimeWithWoodcutter(PIPE_A);

        connectIdentified(restarted, PIPE_A, UUID_A, "Alpha", runtime);

        assertTrue(isRunning(runtime));
        assertTrue(restarted.getStartWhenBackQueue().all().isEmpty());
    }

    @Test
    void anotherAccount_doesNotTakeTheStart() {
        CliContext ctx = newContext();
        ctx.startWhenBack(UUID_A, WOODCUTTER);
        ScriptRuntime other = runtimeWithWoodcutter(PIPE_B);

        connectIdentified(ctx, PIPE_B, UUID_B, "Bravo", other);

        assertFalse(isRunning(other));
        assertEquals(List.of(UUID_A),
                ctx.getStartWhenBackQueue().all().stream().map(QueuedStart::accountUuid).toList());
    }

    @Test
    void aSecondClientOnTheAccount_doesNotTakeTheStart() {
        CliContext ctx = newContext();
        connectIdentified(ctx, PIPE_A, UUID_A, "Alpha", runtimeWithWoodcutter(PIPE_A));
        ctx.getStartWhenBackQueue().enqueue(UUID_A, WOODCUTTER, Instant.EPOCH);
        ScriptRuntime second = runtimeWithWoodcutter(PIPE_B);

        connectIdentified(ctx, PIPE_B, UUID_A, "Alpha", second);

        assertEquals(new ClientKey.Account(UUID_A, 2), ctx.clientKeyOf(PIPE_B));
        assertFalse(isRunning(second));
        assertEquals(1, ctx.getStartWhenBackQueue().all().size());
    }

    @Test
    void queuingForAClientThatIsConnected_startsItNow() {
        CliContext ctx = newContext();
        ScriptRuntime runtime = runtimeWithWoodcutter(PIPE_A);
        connectIdentified(ctx, PIPE_A, UUID_A, "Alpha", runtime);

        ctx.startWhenBack(UUID_A, WOODCUTTER);

        assertTrue(isRunning(runtime));
        assertTrue(ctx.getStartWhenBackQueue().all().isEmpty());
    }

    @Test
    void stoppingTheScriptOnAClientThatIsNotConnected_cancelsTheQueuedStart() {
        CliContext ctx = newContext();
        ctx.startWhenBack(UUID_A, WOODCUTTER);

        OpResult result = ctx.getClientManager().stopScript(UUID_A, WOODCUTTER);

        assertTrue(result.success(), result::toString);
        assertEquals(ClientManager.QUEUED_START_CANCELLED, result.message());
        assertTrue(ctx.getStartWhenBackQueue().all().isEmpty());
        ScriptRuntime runtime = runtimeWithWoodcutter(PIPE_A);
        connectIdentified(ctx, PIPE_A, UUID_A, "Alpha", runtime);
        assertFalse(isRunning(runtime), "a cancelled start must not happen when the client is back");
    }

    @Test
    void stoppingAcrossAGroup_cancelsItsMembersQueuedStarts_only() {
        CliContext ctx = newContext();
        GroupId farm = ctx.getGroupStore().create("farm", Optional.empty()).orElseThrow().id();
        ctx.getGroupStore().addMember(farm, ClientKey.account(UUID_A));
        ctx.startWhenBack(UUID_A, WOODCUTTER);
        ctx.startWhenBack(UUID_B, WOODCUTTER);

        ctx.getClientManager().stopScriptOnGroup("farm", WOODCUTTER);

        assertEquals(List.of(UUID_B),
                ctx.getStartWhenBackQueue().all().stream().map(QueuedStart::accountUuid).toList());
    }

    @Test
    void stoppingEverythingOnAGroup_cancelsEveryStartQueuedOnItsMembers() {
        CliContext ctx = newContext();
        GroupId farm = ctx.getGroupStore().create("farm", Optional.empty()).orElseThrow().id();
        ctx.getGroupStore().addMember(farm, ClientKey.account(UUID_A));
        ctx.startWhenBack(UUID_A, WOODCUTTER);
        ctx.startWhenBack(UUID_A, "Fletcher");

        ctx.getClientManager().stopAllScriptsOnGroup("farm");

        assertTrue(ctx.getStartWhenBackQueue().all().isEmpty());
    }
}
