package com.botwithus.bot.cli;

import com.botwithus.bot.api.event.LoginStateChangeEvent;
import com.botwithus.bot.api.event.ReconnectStateChangedEvent;
import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.core.impl.EventBusImpl;
import com.botwithus.bot.core.impl.MapHelper;
import com.botwithus.bot.core.rpc.RpcClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Keeps each connection's account info, game state and world current.
 *
 * <p>A connection is read once when it is attached, then again whenever its login
 * state changes, whenever its pipe comes back after a drop, and on a slow poll
 * while its state is unsettled or it is in a world (a world hop need not pass
 * through another state). Every read that blocks on the agent runs on the
 * refresh executor, never on the thread that delivered the event, so neither the
 * event pump nor the render thread waits on the pipe.</p>
 *
 * <p>Works with agents old and new: game state comes from the account reply when
 * the agent includes it and from {@code get_login_state} otherwise.</p>
 */
public final class ConnectionStatusTracker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConnectionStatusTracker.class);

    /** How often unsettled and in-world connections are read again. */
    public static final Duration POLL_INTERVAL = Duration.ofSeconds(30);

    private static final String GET_ACCOUNT_INFO = "get_account_info";
    private static final String GET_LOGIN_STATE = "get_login_state";
    private static final String GET_CURRENT_WORLD = "get_current_world";
    private static final String STATE_KEY = "state";
    private static final String WORLD_KEY = "world_id";

    private final Executor refreshExecutor;
    private final Duration pollInterval;
    private final Consumer<Connection> onRefreshed;
    /** Connections with a refresh queued but not yet started, so bursts of events coalesce. */
    private final Set<Connection> queued = ConcurrentHashMap.newKeySet();
    private final Object pollLock = new Object();
    // Guarded by pollLock.
    private Thread pollThread;

    /**
     * @param refreshExecutor runs the blocking reads; each task may block on the pipe
     * @param pollInterval    how often {@link #startPolling} re-reads connections
     */
    public ConnectionStatusTracker(Executor refreshExecutor, Duration pollInterval) {
        this(refreshExecutor, pollInterval, conn -> { });
    }

    /**
     * @param onRefreshed told after every successful {@link #refresh}, on the
     *                    thread that ran it, once the connection holds the new
     *                    account and game status. Must not block; a throw is logged.
     */
    public ConnectionStatusTracker(Executor refreshExecutor, Duration pollInterval,
                                   Consumer<Connection> onRefreshed) {
        this.refreshExecutor = refreshExecutor;
        this.pollInterval = pollInterval;
        this.onRefreshed = onRefreshed;
    }

    /**
     * Starts following {@code conn}: queues a first read, and re-reads it when its
     * login state changes or its pipe reconnects.
     */
    public void attach(Connection conn) {
        EventBusImpl bus = conn.getEventBus();
        if (bus != null) {
            bus.subscribe(LoginStateChangeEvent.class, event -> onLoginStateChange(conn, event));
            bus.subscribe(ReconnectStateChangedEvent.class, event -> onReconnectStateChange(conn, event));
        }
        requestRefresh(conn);
    }

    /** Queues a read of {@code conn} unless one is already queued. Never blocks. */
    public void requestRefresh(Connection conn) {
        if (!queued.add(conn)) {
            return;
        }
        try {
            refreshExecutor.execute(() -> {
                queued.remove(conn);
                refreshQuietly(conn);
            });
        } catch (RejectedExecutionException e) {
            queued.remove(conn);
            log.debug("Status refresh for '{}' not queued: {}", conn.getName(), e.getMessage());
        }
    }

    /**
     * Reads account info, game state and world from the agent and stores them on
     * {@code conn}. The account reply is stored whatever it holds, so the account
     * UUID is kept even before the client has a display name. Blocks on the pipe.
     *
     * @return the account reply, for callers that act on the name
     * @throws RuntimeException if the agent cannot be asked for the account info
     */
    public AccountReply refresh(Connection conn) {
        long ticket = conn.beginStatusRead();
        RpcClient rpc = conn.getRpc();
        Map<String, Object> info = rpc.callSync(GET_ACCOUNT_INFO, Map.of());
        conn.setAccountInfo(info);
        AccountReply reply = new AccountReply(info);
        GameState state = reply.gameState().orElseGet(() -> readLoginState(rpc, conn.getName()));
        boolean inGame = state == GameState.IN_GAME;
        OptionalInt world = inGame ? readWorld(rpc, conn.getName()) : OptionalInt.empty();
        GameStatus status = new GameStatus(state, world, inGame && reply.isMember());
        conn.publishReading(ticket, status, inGame ? reply.inGameName() : Optional.empty(),
                reply.launchedName());
        notifyRefreshed(conn);
        return reply;
    }

    /** Queues a read of every live connection that {@link #needsPolling needs one}. */
    public void pollOnce(List<Connection> connections) {
        for (Connection conn : connections) {
            if (conn.isAlive() && needsPolling(conn.getGameState())) {
                requestRefresh(conn);
            }
        }
    }

    /**
     * Whether a connection in {@code state} is re-read on the poll. An unsettled
     * state may settle without an event reaching the host; a world can change by a
     * hop. The login screen and lobby change only through events.
     */
    static boolean needsPolling(GameState state) {
        return state == GameState.UNKNOWN || state == GameState.IN_GAME;
    }

    /** Starts the background poll over {@code connections}, once. */
    public void startPolling(Supplier<List<Connection>> connections) {
        synchronized (pollLock) {
            if (pollThread != null) {
                return;
            }
            pollThread = Thread.ofVirtual().name("connection-status-poll")
                    .start(() -> pollLoop(connections));
        }
    }

    /** Stops the background poll. Queued reads still run. */
    @Override
    public void close() {
        synchronized (pollLock) {
            if (pollThread != null) {
                pollThread.interrupt();
                pollThread = null;
            }
        }
    }

    private void pollLoop(Supplier<List<Connection>> connections) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(pollInterval);
                pollOnce(connections.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The event already names the new state, so it is applied at once; the world
     * and account follow from a read.
     */
    private void onLoginStateChange(Connection conn, LoginStateChangeEvent event) {
        GameState next = GameState.fromWire(event.newState());
        conn.publishGameStatus(conn.beginStatusRead(), previous -> previous.withState(next));
        requestRefresh(conn);
    }

    private void onReconnectStateChange(Connection conn, ReconnectStateChangedEvent event) {
        switch (event.state()) {
            case ReconnectState.Connected ignored -> requestRefresh(conn);
            case ReconnectState.Disconnected ignored -> { }
            case ReconnectState.Reconnecting ignored -> { }
            case ReconnectState.GivingUp ignored -> { }
        }
    }

    private void notifyRefreshed(Connection conn) {
        try {
            onRefreshed.accept(conn);
        } catch (RuntimeException e) {
            log.warn("Status refresh listener threw for '{}': {}", conn.getName(), e.toString());
        }
    }

    private void refreshQuietly(Connection conn) {
        try {
            refresh(conn);
        } catch (RuntimeException e) {
            log.debug("Status refresh for '{}' failed: {}", conn.getName(), e.getMessage());
        }
    }

    private static GameState readLoginState(RpcClient rpc, String connectionName) {
        try {
            Map<String, Object> reply = rpc.callSync(GET_LOGIN_STATE, Map.of());
            return GameState.fromWire(MapHelper.getIntOr(reply, STATE_KEY, 0));
        } catch (RuntimeException e) {
            log.debug("get_login_state failed for '{}': {}", connectionName, e.getMessage());
            return GameState.UNKNOWN;
        }
    }

    private static OptionalInt readWorld(RpcClient rpc, String connectionName) {
        try {
            Map<String, Object> reply = rpc.callSync(GET_CURRENT_WORLD, Map.of());
            int world = MapHelper.getIntOr(reply, WORLD_KEY, 0);
            return world > 0 ? OptionalInt.of(world) : OptionalInt.empty();
        } catch (RuntimeException e) {
            log.debug("get_current_world failed for '{}': {}", connectionName, e.getMessage());
            return OptionalInt.empty();
        }
    }
}
