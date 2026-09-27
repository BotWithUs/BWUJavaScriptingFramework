package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.core.pipe.PipeException;
import com.botwithus.bot.core.rpc.PipeResolution;
import com.botwithus.bot.core.rpc.ReconnectController;
import com.botwithus.bot.core.rpc.ReconnectPolicy;
import com.botwithus.bot.core.runtime.ScriptRuntime;
import com.botwithus.bot.core.sdn.InstalledScriptsLedger;
import com.botwithus.bot.core.sdn.SdnCatalogueRefresher;
import com.botwithus.bot.core.sdn.SdnCatalogueResult;
import com.botwithus.bot.core.sdn.SdnInstaller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.InstantSource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a card shows and does for a client whose pipe dropped. The board reads a
 * real {@link ReconnectController}; only the connection around it is a mock.
 */
class LiveClientBoardReconnectTest {

    private static final String CLIENT = "BotWithUs_1";
    private static final double MID_JITTER = 0.5;
    private static final long LONG_BACKOFF_MS = 600_000L;
    private static final long PROMPT_MS = 5_000L;
    private static final long POLL_MS = 5L;
    private static final int BUDGET = 5;

    @TempDir
    Path tempDir;

    private final Queue<Runnable> commandQueue = new ArrayDeque<>();
    private final Executor queued = commandQueue::add;
    private final List<ReconnectController> controllers = new ArrayList<>();
    private CliContext ctx;
    private LiveClientBoard board;

    @BeforeEach
    void setUp() {
        ctx = mock(CliContext.class);
        SdnCatalogueRefresher catalogue = new SdnCatalogueRefresher(
                () -> new SdnCatalogueResult.Delivered(List.of(), false), Runnable::run,
                InstantSource.system(), () -> MID_JITTER);
        board = new LiveClientBoard(ctx, id -> { }, Clock.systemUTC(), catalogue,
                new SdnInstaller(tempDir, new InstalledScriptsLedger(tempDir, InstantSource.system())),
                queued);
        drain();
    }

    @AfterEach
    void closeControllers() {
        controllers.forEach(ReconnectController::close);
    }

    @Test
    void anUnlimitedPolicyShowsNoAttemptCap() {
        ReconnectController controller = reconnecting(policy(ReconnectPolicy.UNLIMITED));
        connect(controller);

        ClientStatus.Reconnecting shown = reconnectingStatus(onlyStatus());

        assertEquals(OptionalInt.empty(), shown.maxAttempts(), "no \"of 2147483647\" on the card");
    }

    @Test
    void aCappedPolicyShowsItsCap() {
        ReconnectController controller = reconnecting(policy(BUDGET));
        connect(controller);

        assertEquals(OptionalInt.of(BUDGET), reconnectingStatus(onlyStatus()).maxAttempts());
    }

    @Test
    void stopRetrying_stopsTheControllerSoEveryViewSeesIt() {
        ReconnectController controller = reconnecting(policy(ReconnectPolicy.UNLIMITED));
        connect(controller);

        board.actions().stopRetrying(CLIENT);

        awaitState(controller, LiveClientBoardReconnectTest::isGivingUp);
        ClientStatus.Lost lost = lostStatus(onlyStatus());
        assertTrue(lost.canRetry());
    }

    @Test
    void retryNow_afterGivingUp_restartsTheControllersRecovery() {
        AtomicInteger attempts = new AtomicInteger();
        ReconnectController controller = gaveUp(new PipeResolution.Found(CLIENT), attempts);
        connect(controller);
        int before = attempts.get();

        board.actions().retryNow(CLIENT);

        awaitState(controller, state -> attempts.get() > before);
        verify(ctx, never()).connect(any());
    }

    @Test
    void aClientWhoseProcessExitedOffersForgetInsteadOfRetry() {
        ReconnectController controller = gaveUp(
                new PipeResolution.Gone(PipeResolution.Gone.Reason.PROCESS_EXITED, "exited"),
                new AtomicInteger());
        connect(controller);

        assertFalse(lostStatus(onlyStatus()).canRetry());
    }

    @Test
    void forget_runsOnTheCommandExecutor() {
        board.actions().forget(CLIENT);

        verify(ctx, never()).forget(anyString());
        drain();
        verify(ctx).forget(CLIENT);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static ReconnectPolicy policy(int maxAttempts) {
        return new ReconnectPolicy(maxAttempts, LONG_BACKOFF_MS, 1.0, LONG_BACKOFF_MS);
    }

    /** A controller mid back-off, as after a real drop. */
    private ReconnectController reconnecting(ReconnectPolicy policy) {
        AtomicReference<Consumer<Throwable>> handler = new AtomicReference<>();
        ReconnectController controller = new ReconnectController(handler::set, pipe -> { },
                attempt -> new PipeResolution.Found(CLIENT), CLIENT, policy, state -> { }, ev -> { });
        controllers.add(controller);
        controller.arm();
        handler.get().accept(new PipeException("drop"));
        awaitState(controller, LiveClientBoardReconnectTest::isReconnecting);
        return controller;
    }

    /** A controller that gave up after one attempt the resolver answered with {@code verdict}. */
    private ReconnectController gaveUp(PipeResolution verdict, AtomicInteger attempts) {
        AtomicReference<Consumer<Throwable>> handler = new AtomicReference<>();
        ReconnectController.Reconnector failing = pipe -> {
            attempts.incrementAndGet();
            throw new PipeException("down");
        };
        ReconnectController controller = new ReconnectController(handler::set, failing,
                attempt -> verdict, CLIENT, new ReconnectPolicy(1, 0, 1.0, 0), state -> { }, ev -> { });
        controllers.add(controller);
        controller.arm();
        handler.get().accept(new PipeException("drop"));
        awaitState(controller, LiveClientBoardReconnectTest::isGivingUp);
        return controller;
    }

    /** A dead connection whose recovery {@code controller} runs. */
    private void connect(ReconnectController controller) {
        Connection conn = mock(Connection.class);
        ScriptRuntime runtime = mock(ScriptRuntime.class);
        when(runtime.getRunners()).thenReturn(List.of());
        when(conn.getName()).thenReturn(CLIENT);
        when(conn.getRuntime()).thenReturn(runtime);
        when(conn.isAlive()).thenReturn(false);
        when(conn.getReconnectController()).thenReturn(controller);
        when(conn.currentReconnectState()).thenAnswer(call -> controller.currentState());
        when(conn.getWorldId()).thenReturn(OptionalInt.empty());
        when(ctx.getConnections()).thenReturn(List.of(conn));
    }

    private ClientStatus onlyStatus() {
        List<ClientView> views = board.clients();
        assertEquals(1, views.size());
        return views.getFirst().status();
    }

    private static ClientStatus.Reconnecting reconnectingStatus(ClientStatus status) {
        return switch (status) {
            case ClientStatus.Reconnecting r -> r;
            default -> throw new AssertionError("expected reconnecting, got " + status);
        };
    }

    private static ClientStatus.Lost lostStatus(ClientStatus status) {
        return switch (status) {
            case ClientStatus.Lost l -> l;
            default -> throw new AssertionError("expected lost, got " + status);
        };
    }

    private static boolean isReconnecting(ReconnectState state) {
        return switch (state) {
            case ReconnectState.Reconnecting r -> true;
            default -> false;
        };
    }

    private static boolean isGivingUp(ReconnectState state) {
        return switch (state) {
            case ReconnectState.GivingUp g -> true;
            default -> false;
        };
    }

    private static void awaitState(ReconnectController controller, Predicate<ReconnectState> match) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROMPT_MS);
        while (!match.test(controller.currentState())) {
            assertTrue(System.nanoTime() < deadline, "never reached; at " + controller.currentState());
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted", e);
            }
        }
    }

    private void drain() {
        Runnable task;
        while ((task = commandQueue.poll()) != null) {
            task.run();
        }
    }
}
