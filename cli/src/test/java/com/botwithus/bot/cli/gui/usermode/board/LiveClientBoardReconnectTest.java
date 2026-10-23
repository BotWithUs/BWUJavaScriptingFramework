package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.events.ClientKey;
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
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a card shows and does for a client whose pipe dropped. The board reads a
 * real client registry and a real {@link ReconnectController}; only the
 * connection around them is a mock. A card is addressed by its account, and the
 * board finds the pipe and the controller behind it.
 */
class LiveClientBoardReconnectTest {

    private static final String PIPE = "BotWithUs_1";
    private static final String UUID = "0123456789abcdef0123456789abcdef";
    private static final ClientKey ACCOUNT = ClientKey.account(UUID);
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
        drain();
    }

    @AfterEach
    void closeControllers() {
        controllers.forEach(ReconnectController::close);
    }

    @Test
    void aReconnectingClient_showsNotResponding_withNoCapUnderAnUnlimitedPolicy() {
        ReconnectController controller = reconnecting(policy(ReconnectPolicy.UNLIMITED));
        connect(controller);

        ClientState.NotResponding shown = notResponding(onlyView().state());

        assertAll(
                () -> assertEquals(OptionalInt.empty(), shown.maxAttempts(), "no \"of 2147483647\" on the card"),
                () -> assertTrue(shown.isRetrying()),
                () -> assertTrue(shown.attempt() >= 1));
    }

    @Test
    void aCappedPolicyShowsItsCap() {
        ReconnectController controller = reconnecting(policy(BUDGET));
        connect(controller);

        assertEquals(OptionalInt.of(BUDGET), notResponding(onlyView().state()).maxAttempts());
    }

    @Test
    void stopRetrying_byAccount_stopsThatClientsController() {
        ReconnectController controller = reconnecting(policy(ReconnectPolicy.UNLIMITED));
        connect(controller);

        board.actions().stopRetrying(ACCOUNT);

        awaitState(controller, LiveClientBoardReconnectTest::isGivingUp);
        registry.linkState(ACCOUNT, PIPE, controller.currentState());
        assertEquals(Optional.empty(), notResponding(onlyView().state()).nextIn(),
                "stopped, but the game may still be running, so the card still offers a retry");
    }

    @Test
    void retryNow_afterGivingUp_restartsTheControllersRecovery() {
        AtomicInteger attempts = new AtomicInteger();
        ReconnectController controller = gaveUp(new PipeResolution.Found(PIPE), attempts);
        connect(controller);
        int before = attempts.get();

        board.actions().retryNow(ACCOUNT);

        awaitState(controller, state -> attempts.get() > before);
        verify(ctx, never()).connect(any());
    }

    @Test
    void aClientWhoseProcessExited_isClosed_andKeepsItsPipeSoItCanBeDismissed() {
        ReconnectController controller = gaveUp(
                new PipeResolution.Gone(PipeResolution.Gone.Reason.PROCESS_EXITED, "exited"),
                new AtomicInteger());
        connect(controller);

        ClientView view = onlyView();

        assertAll(
                () -> assertTrue(view.isClosed(), "got " + view.state()),
                () -> assertEquals(Optional.of(PIPE), view.pipe()));
    }

    @Test
    void forget_runsOnTheCommandExecutor_withTheCardsKey() {
        board.actions().forget(ACCOUNT);

        verify(ctx, never()).forget(any(ClientKey.class));
        verify(ctx, never()).forget(anyString());
        drain();
        verify(ctx).forget(ACCOUNT);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static ReconnectPolicy policy(int maxAttempts) {
        return new ReconnectPolicy(maxAttempts, LONG_BACKOFF_MS, 1.0, LONG_BACKOFF_MS);
    }

    /** A controller mid back-off, as after a real drop. */
    private ReconnectController reconnecting(ReconnectPolicy policy) {
        AtomicReference<Consumer<Throwable>> handler = new AtomicReference<>();
        ReconnectController controller = new ReconnectController(handler::set, pipe -> { },
                attempt -> new PipeResolution.Found(PIPE), PIPE, policy, state -> { }, ev -> { });
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
                attempt -> verdict, PIPE, new ReconnectPolicy(1, 0, 1.0, 0), state -> { }, ev -> { });
        controllers.add(controller);
        controller.arm();
        handler.get().accept(new PipeException("drop"));
        awaitState(controller, LiveClientBoardReconnectTest::isGivingUp);
        return controller;
    }

    /**
     * A dead connection on an identified account whose recovery {@code controller}
     * runs, with the controller's state reported to the registry as the host would.
     */
    private void connect(ReconnectController controller) {
        Connection conn = mock(Connection.class);
        ScriptRuntime runtime = mock(ScriptRuntime.class);
        when(runtime.getRunners()).thenReturn(List.of());
        when(conn.getName()).thenReturn(PIPE);
        when(conn.getRuntime()).thenReturn(runtime);
        when(conn.isAlive()).thenReturn(false);
        when(conn.getReconnectController()).thenReturn(controller);
        when(conn.currentReconnectState()).thenAnswer(call -> controller.currentState());
        when(ctx.getConnections()).thenReturn(List.of(conn));
        registry.connect(conn, UUID, "Oakheart");
        registry.linkState(ACCOUNT, PIPE, controller.currentState());
    }

    private ClientView onlyView() {
        List<ClientView> views = board.clients();
        assertEquals(1, views.size());
        return views.getFirst();
    }

    private static ClientState.NotResponding notResponding(ClientState state) {
        return switch (state) {
            case ClientState.NotResponding r -> r;
            default -> throw new AssertionError("expected not responding, got " + state);
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
