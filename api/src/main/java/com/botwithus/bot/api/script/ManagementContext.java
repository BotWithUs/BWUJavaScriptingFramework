package com.botwithus.bot.api.script;

import com.botwithus.bot.api.ClientProvider;
import com.botwithus.bot.api.isc.MessageBus;
import com.botwithus.bot.api.isc.SharedState;

/**
 * Context passed to {@link ManagementScript#onStart(ManagementContext)}.
 *
 * <p>Unlike {@link com.botwithus.bot.api.ScriptContext}, this context is
 * <b>not tied to any single client</b>. It provides cross-client coordination
 * via {@link ClientOrchestrator} and per-client access via {@link ClientProvider}.
 *
 * @see ManagementScript
 * @see ClientOrchestrator
 */
public interface ManagementContext {

    /**
     * Returns the orchestrator for cross-client script and group management.
     */
    ClientOrchestrator getOrchestrator();

    /**
     * Returns the client provider for accessing individual connected clients
     * and their GameAPI / EventBus instances.
     */
    ClientProvider getClientProvider();

    /**
     * Returns the message bus for inter-script communication.
     */
    MessageBus getMessageBus();

    /**
     * Returns the shared state store for cross-script data sharing.
     */
    SharedState getSharedState();
}
