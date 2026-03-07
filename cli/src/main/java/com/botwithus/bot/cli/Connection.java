package com.botwithus.bot.cli;

import com.botwithus.bot.core.impl.EventBusImpl;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRuntime;

public class Connection {

    private final String name;
    private final PipeClient pipe;
    private final RpcClient rpc;
    private final ScriptRuntime runtime;
    private EventBusImpl eventBus;

    public Connection(String name, PipeClient pipe, RpcClient rpc, ScriptRuntime runtime) {
        this.name = name;
        this.pipe = pipe;
        this.rpc = rpc;
        this.runtime = runtime;
    }

    public String getName() { return name; }
    public PipeClient getPipe() { return pipe; }
    public RpcClient getRpc() { return rpc; }
    public ScriptRuntime getRuntime() { return runtime; }

    public void setEventBus(EventBusImpl eventBus) { this.eventBus = eventBus; }
    public EventBusImpl getEventBus() { return eventBus; }

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
        try { runtime.stopAll(); } catch (Exception ignored) {}
        try { rpc.close(); } catch (Exception ignored) {}
    }
}
