package com.botwithus.bot.cli;

import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.api.script.ScriptScheduler;
import com.botwithus.bot.core.impl.EventBusImpl;
import com.botwithus.bot.core.impl.GameAPIImpl;
import com.botwithus.bot.core.impl.ScriptManagerImpl;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.ReconnectController;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRuntime;
import com.botwithus.bot.core.shm.SharedRegionEventPump;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

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
    private String accountName;
    private Map<String, Object> accountInfo;
    private boolean lobbyLoginAttempted;

    public Connection(String name, PipeClient pipe, RpcClient rpc, ScriptRuntime runtime, ScriptManagerImpl scriptManager) {
        this.name = name;
        this.pipe = pipe;
        this.rpc = rpc;
        this.runtime = runtime;
        this.scriptManager = scriptManager;
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

    /** Current reconnect state; {@code null} if no controller is attached. */
    public ReconnectState currentReconnectState() {
        return reconnectController != null ? reconnectController.currentState() : null;
    }

    public void setAccountName(String accountName) { this.accountName = accountName; }
    public String getAccountName() { return accountName; }

    public void setAccountInfo(Map<String, Object> accountInfo) { this.accountInfo = accountInfo; }
    public Map<String, Object> getAccountInfo() { return accountInfo; }

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
