package com.botwithus.bot.cli;

import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.api.script.ScriptScheduler;
import com.botwithus.bot.core.impl.EventBusImpl;
import com.botwithus.bot.core.impl.GameAPIImpl;
import com.botwithus.bot.core.impl.ScriptContextChannel;
import com.botwithus.bot.core.impl.ScriptManagerImpl;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.ReconnectController;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.runlog.KnownNames;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRuntime;
import com.botwithus.bot.core.shm.SharedRegionEventPump;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

public class Connection {

    private static final Logger log = LoggerFactory.getLogger(Connection.class);

    private final String name;
    private final PipeClient pipe;
    private final RpcClient rpc;
    private final ScriptRuntime runtime;
    private final ScriptManagerImpl scriptManager;
    private EventBusImpl eventBus;
    private SharedRegionEventPump eventPump;
    private ReconnectController reconnectController;
    private GameAPIImpl gameAPI;
    private ScriptContextChannel scriptContextChannel;
    // Written by the pipe scanner and commands once the agent identifies the
    // account, read every frame by the GUI: volatile so readers see the update.
    private volatile String accountName;
    private volatile String accountUuid;
    private volatile Map<String, Object> accountInfo;
    private volatile boolean lobbyLoginAttempted;
    // Lifecycle and game state: written by the status tracker and the reconnect
    // listener, read every frame by the GUI. Each is one immutable value behind a
    // volatile reference, so a reader never sees half of an update.
    private final Instant connectedAt;
    private volatile Instant lastReconnectedAt;
    private volatile GameStatus gameStatus = GameStatus.UNKNOWN;
    /** Orders status reads, so an older reading never overwrites a newer one. */
    private final AtomicLong statusReadCounter = new AtomicLong();
    private final Object statusLock = new Object();
    // Guarded by statusLock: the read that produced the current gameStatus.
    private long publishedStatusRead;
    // Written under statusLock alongside gameStatus, read lock-free by script
    // threads; null while no in-world reading newer than the last state change exists.
    private volatile String inGameName;
    // Written under statusLock by an accepted reading, read lock-free. The Jagex
    // character the process was launched for: fixed for the process, so it is
    // kept across state changes. Null until a reading carries it.
    private volatile String launchedName;
    // Guarded by statusLock: whether the launched/logged-in mismatch was logged.
    private boolean loggedNameMismatch;

    public Connection(String name, PipeClient pipe, RpcClient rpc, ScriptRuntime runtime, ScriptManagerImpl scriptManager) {
        this(name, pipe, rpc, runtime, scriptManager, Instant.now());
    }

    /** @param connectedAt when the host connected to this client's pipe */
    public Connection(String name, PipeClient pipe, RpcClient rpc, ScriptRuntime runtime,
                      ScriptManagerImpl scriptManager, Instant connectedAt) {
        this.name = name;
        this.pipe = pipe;
        this.rpc = rpc;
        this.runtime = runtime;
        this.scriptManager = scriptManager;
        this.connectedAt = connectedAt;
    }

    public String getName() { return name; }
    public PipeClient getPipe() { return pipe; }
    public RpcClient getRpc() { return rpc; }
    public ScriptRuntime getRuntime() { return runtime; }
    public ScriptScheduler getScheduler() { return scriptManager.getScheduler(); }

    public void setEventBus(EventBusImpl eventBus) { this.eventBus = eventBus; }
    public EventBusImpl getEventBus() { return eventBus; }

    public void setEventPump(SharedRegionEventPump pump) { this.eventPump = pump; }
    public SharedRegionEventPump getEventPump() { return eventPump; }

    public void setReconnectController(ReconnectController controller) { this.reconnectController = controller; }
    public ReconnectController getReconnectController() { return reconnectController; }

    public void setGameAPI(GameAPIImpl gameAPI) { this.gameAPI = gameAPI; }
    public GameAPIImpl getGameAPI() { return gameAPI; }

    public void setScriptContextChannel(ScriptContextChannel channel) { this.scriptContextChannel = channel; }
    public ScriptContextChannel getScriptContextChannel() { return scriptContextChannel; }

    /** Current reconnect state; {@code null} if no controller is attached. */
    public ReconnectState currentReconnectState() {
        return reconnectController != null ? reconnectController.currentState() : null;
    }

    public void setAccountName(String accountName) { this.accountName = accountName; }
    public String getAccountName() { return accountName; }

    /**
     * Stable per-account identifier sourced from the agent's {@code get_account_info}
     * reply ({@code account_uuid} field, populated by the loader's {@code BotDetails}).
     * Used as the storage key for AutoStart profiles and per-script configs so the
     * keying survives in-game display-name changes. Returns {@code null} when the
     * agent reply did not carry the field (older builds).
     */
    public String getAccountUuid() { return accountUuid; }

    /**
     * The account UUID when it identifies a real account: empty when the agent
     * sent none, or sent the placeholder a development launch produces (see
     * {@link AccountReply#identifiedUuid()}). This is the one to key anything by.
     */
    public Optional<String> getIdentifiedUuid() {
        return AccountReply.identified(accountUuid);
    }

    /**
     * The best name to show for this client from the last account reply, which a
     * newer agent can supply before the client logs in. Empty until one is known.
     * {@link #getAccountName()} is different: auto-start sets it once it has
     * identified the client.
     */
    public Optional<String> getDisplayName() {
        Map<String, Object> info = accountInfo;
        return info != null ? new AccountReply(info).displayName() : Optional.empty();
    }

    /**
     * The character this client is logged in as, else the one it was launched for.
     *
     * <p>The logged-in name ({@link #getInGameName()}) wins whenever it is known.
     * Without it, this is the Jagex character the process was launched for
     * ({@link AccountReply#launchedName()}), which is known from the login screen
     * on. Empty when neither is known: before the first reading, and outside a
     * world for a client the Jagex launcher did not start. Never touches the pipe.</p>
     */
    public Optional<String> getCharacterName() {
        String loggedIn = inGameName;
        return Optional.ofNullable(loggedIn != null ? loggedIn : launchedName);
    }

    /**
     * The character this client is logged in as ({@link AccountReply#inGameName()}),
     * from the newest status reading taken in a world. Empty until such a reading
     * has been published, and dropped whenever the game state changes, so neither
     * a logout nor a relog as someone else leaves the previous character's name
     * behind while the follow-up read is in flight. Never touches the pipe.
     */
    Optional<String> getInGameName() {
        return Optional.ofNullable(inGameName);
    }

    /**
     * Stores an unmodifiable copy of the agent's {@code get_account_info} reply, so
     * a reader on another thread never sees the map change underneath it. When the
     * reply identifies the account, the script runtime is bound to it too, so
     * per-script config persists under that account however the host connected.
     */
    public void setAccountInfo(Map<String, Object> accountInfo) {
        if (accountInfo == null) {
            this.accountInfo = null;
            return;
        }
        Map<String, Object> copy = Collections.unmodifiableMap(new LinkedHashMap<>(accountInfo));
        Object uuid = copy.get("account_uuid");
        if (uuid != null) {
            this.accountUuid = uuid.toString();
        }
        this.accountInfo = copy;
        getIdentifiedUuid().ifPresent(this::bindRuntimeTo);
    }

    private void bindRuntimeTo(String uuid) {
        if (!uuid.equals(runtime.getAccountUuid())) {
            runtime.setAccountUuid(uuid);
        }
    }

    /**
     * Every name this host knows for the client, for a script run log to redact:
     * the connection's own name and the launcher's account name as accounts,
     * the logged-in and launched-for character names as players. Read for every
     * line a run log writes, so it reads only fields already in memory.
     */
    public KnownNames knownNames() {
        List<String> accounts = new ArrayList<>();
        List<String> players = new ArrayList<>();
        accounts.add(name);
        accounts.add(accountName);
        players.add(inGameName);
        players.add(launchedName);
        Map<String, Object> info = accountInfo;
        if (info != null) {
            AccountReply reply = new AccountReply(info);
            reply.accountName().ifPresent(accounts::add);
            reply.inGameName().ifPresent(players::add);
            reply.launchedName().ifPresent(players::add);
        }
        return KnownNames.of(accounts, players);
    }

    /** The last {@code get_account_info} reply, unmodifiable; {@code null} until the account is probed. */
    public Map<String, Object> getAccountInfo() { return accountInfo; }

    /** When the host connected to this client. A reconnect of the same pipe keeps it. */
    public Instant getConnectedAt() { return connectedAt; }

    /** When the pipe last came back after a drop; empty if it never dropped. */
    public Optional<Instant> getLastReconnectedAt() { return Optional.ofNullable(lastReconnectedAt); }

    /**
     * Records a reconnect-state change. Only a return to {@code Connected} is kept:
     * the controller publishes nothing until the first drop, so every
     * {@code Connected} it reports is a reconnect.
     */
    void onReconnectState(ReconnectState state) {
        switch (state) {
            case ReconnectState.Connected connected ->
                    lastReconnectedAt = Instant.ofEpochMilli(connected.timestamp());
            case ReconnectState.Disconnected ignored -> { }
            case ReconnectState.Reconnecting ignored -> { }
            case ReconnectState.GivingUp ignored -> { }
        }
    }

    /** Game state, world and membership, as last refreshed. */
    public GameStatus getGameStatus() { return gameStatus; }

    public GameState getGameState() { return gameStatus.state(); }

    /** The world the client is in; empty unless it is in a world. */
    public OptionalInt getWorldId() { return gameStatus.world(); }

    /** Whether the account is a member; {@code false} until the client is in a world. */
    public boolean isMember() { return gameStatus.isMember(); }

    /**
     * Starts a reading of the game status. Take the ticket <em>before</em> asking
     * the agent, and hand it to {@link #publishGameStatus}: two readings can finish
     * out of order, and the ticket is how the later-started one wins.
     */
    long beginStatusRead() {
        return statusReadCounter.incrementAndGet();
    }

    /**
     * Applies {@code update} unless a reading that started after {@code ticket}
     * has already been published.
     *
     * @return whether the update was applied
     */
    boolean publishGameStatus(long ticket, UnaryOperator<GameStatus> update) {
        synchronized (statusLock) {
            if (ticket < publishedStatusRead) {
                return false;
            }
            publishedStatusRead = ticket;
            GameStatus next = update.apply(gameStatus);
            if (next.state() != gameStatus.state()) {
                inGameName = null;
            }
            gameStatus = next;
            return true;
        }
    }

    /**
     * Publishes a full reading: its game status, as {@link #publishGameStatus}
     * would, and the names read with it. All land under one ticket, so a name can
     * never be older than the state it is shown alongside.
     *
     * @param inGameName   the in-game name the reading saw; empty outside a world
     * @param launchedName the character the process was launched for, if the
     *                     reading carried one; an empty one keeps what is known
     * @return whether the reading was applied
     */
    boolean publishReading(long ticket, GameStatus status, Optional<String> inGameName,
                           Optional<String> launchedName) {
        synchronized (statusLock) {
            if (!publishGameStatus(ticket, previous -> status)) {
                return false;
            }
            this.inGameName = inGameName.orElse(null);
            launchedName.ifPresent(launched -> this.launchedName = launched);
            logNameMismatchOnce();
            return true;
        }
    }

    /** Notes once per connection that the client was relogged as someone else. Holds statusLock. */
    private void logNameMismatchOnce() {
        String loggedIn = inGameName;
        String launched = launchedName;
        if (loggedNameMismatch || loggedIn == null || launched == null || loggedIn.equals(launched)) {
            return;
        }
        loggedNameMismatch = true;
        log.info("'{}' was launched for {} but is logged in as {}; reporting {}",
                name, launched, loggedIn, loggedIn);
    }

    /**
     * Whether the auto-discovery loop has already dispatched a
     * {@code login_to_lobby} kick for this connection. The flag prevents
     * the scan loop from re-issuing the RPC on every tick once a kick is
     * in flight; it's cleared when the connection is closed and recreated.
     */
    public boolean isLobbyLoginAttempted() { return lobbyLoginAttempted; }
    public void setLobbyLoginAttempted(boolean attempted) { this.lobbyLoginAttempted = attempted; }

    /** Returns true if the underlying pipe is still open. */
    public boolean isAlive() {
        return pipe.isOpen();
    }

    /** Returns true if any scripts are currently running on this connection. */
    public boolean hasRunningScripts() {
        return runtime.getRunners().stream().anyMatch(ScriptRunner::isRunning);
    }

    /** Stop all scripts AND close the connection. */
    public void close() {
        if (reconnectController != null) {
            try {
                reconnectController.close();
            } catch (RuntimeException e) {
                log.error("Error closing reconnect controller for {}", name, e);
            }
        }
        try {
            scriptManager.shutdown();
        } catch (RuntimeException e) {
            log.error("Error shutting down scheduler for {}", name, e);
        }
        try {
            runtime.stopAll();
        } catch (RuntimeException e) {
            log.error("Error stopping scripts for {}", name, e);
        }
        if (eventPump != null) {
            try {
                eventPump.close();
            } catch (RuntimeException e) {
                log.error("Error stopping event pump for {}", name, e);
            }
        }
        if (scriptContextChannel != null) {
            try {
                scriptContextChannel.close();
            } catch (RuntimeException e) {
                log.error("Error closing script-context channel for {}", name, e);
            }
        }
        try {
            rpc.close();
        } catch (RuntimeException e) {
            log.error("Error closing RPC for {}", name, e);
        }
        if (gameAPI != null) {
            try {
                gameAPI.closeWorldWalker();
            } catch (RuntimeException e) {
                log.error("Error closing WorldWalker for {}", name, e);
            }
        }
    }
}
