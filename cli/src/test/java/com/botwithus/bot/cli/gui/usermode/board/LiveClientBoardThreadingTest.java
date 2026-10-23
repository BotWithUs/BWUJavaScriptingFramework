package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.core.runtime.ScriptRuntime;
import com.botwithus.bot.core.sdn.InstalledScriptsLedger;
import com.botwithus.bot.core.sdn.SdnCatalogueRefresher;
import com.botwithus.bot.core.sdn.SdnCatalogueResult;
import com.botwithus.bot.core.sdn.SdnInstaller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

import java.nio.file.Path;
import java.time.Clock;
import java.time.InstantSource;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The board is called from the render thread. Reconnecting and loading the
 * script catalogue block on the pipe and the disk, so they must be handed to the
 * command executor rather than run by the caller. The executor here is a queue
 * the test drains by hand, which makes "ran on the caller" directly observable.
 */
class LiveClientBoardThreadingTest {

    private static final String CLIENT = "BotWithUs_1";
    private static final double MID_JITTER = 0.5;

    @ScriptManifest(name = "Alpha")
    static final class Alpha implements BotScript {
        @Override public void onStart(ScriptContext ctx) { }
        @Override public int onLoop() { return -1; }
        @Override public void onStop() { }
    }

    @ScriptManifest(name = "Beta")
    static final class Beta implements BotScript {
        @Override public void onStart(ScriptContext ctx) { }
        @Override public int onLoop() { return -1; }
        @Override public void onStop() { }
    }

    @TempDir
    Path tempDir;

    private final Queue<Runnable> commandQueue = new ArrayDeque<>();
    private final Executor queued = commandQueue::add;
    private final BoardRegistry registry = new BoardRegistry(Clock.systemUTC());
    private CliContext ctx;
    private LiveClientBoard board;

    @BeforeEach
    void setUp() {
        ctx = mock(CliContext.class);
        when(ctx.getClientRegistry()).thenReturn(registry.registry);
        SdnCatalogueRefresher catalogue = new SdnCatalogueRefresher(
                () -> new SdnCatalogueResult.Delivered(List.of(), false), Runnable::run,
                InstantSource.system(), () -> MID_JITTER);
        board = new LiveClientBoard(ctx, id -> { }, Clock.systemUTC(), catalogue,
                new SdnInstaller(tempDir, new InstalledScriptsLedger(tempDir, InstantSource.system())),
                queued);
    }

    @Test
    void reconnectReturnsBeforeTouchingThePipeAndRunsOnTheCommandExecutor() {
        drain();
        ClientKey client = registry.open(connectionOn(mock(ScriptRuntime.class)));

        board.actions().reconnect(client);

        verify(ctx, never()).disconnect(any(), anyBoolean());
        verify(ctx, never()).connect(any());
        drain();
        InOrder order = inOrder(ctx);
        order.verify(ctx).disconnect(CLIENT, true);
        order.verify(ctx).connect(CLIENT);
    }

    @Test
    void catalogAnswersFromTheLastLoadAndNeverLoadsOnTheCaller() {
        when(ctx.loadScripts()).thenReturn(List.of(new Alpha()));

        assertTrue(board.catalog().isEmpty(), "nothing has loaded yet");
        board.catalog();
        verify(ctx, never()).loadScripts();
        assertEquals(1, commandQueue.size(), "repeated requests share one queued load");

        drain();
        verify(ctx, times(1)).loadScripts();
        assertEquals(List.of("Alpha"), board.catalog().stream().map(e -> e.info().name()).toList());
    }

    @Test
    void startScriptFollowsTheScriptByNameWhenAReloadShiftedTheList() {
        Alpha alpha = new Alpha();
        when(ctx.loadScripts()).thenReturn(List.of(alpha));
        drain();
        ScriptEntry alphaRow = board.catalog().getFirst();
        when(ctx.loadScripts()).thenReturn(List.of(new Beta(), alpha));
        drain();

        ScriptRuntime runtime = mock(ScriptRuntime.class);
        ClientKey client = registry.open(connectionOn(runtime));

        board.actions().startScript(client, alphaRow);

        verify(runtime).startScript(alpha);
    }

    /** A connection on {@link #CLIENT} running {@code runtime}, registered with the host. */
    private Connection connectionOn(ScriptRuntime runtime) {
        Connection conn = mock(Connection.class);
        when(conn.getName()).thenReturn(CLIENT);
        when(conn.getRuntime()).thenReturn(runtime);
        when(ctx.getConnections()).thenReturn(List.of(conn));
        return conn;
    }

    private void drain() {
        Runnable task;
        while ((task = commandQueue.poll()) != null) {
            task.run();
        }
    }
}
