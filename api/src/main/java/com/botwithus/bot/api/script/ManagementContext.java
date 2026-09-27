package com.botwithus.bot.api.script;

import com.botwithus.bot.api.ClientProvider;
import com.botwithus.bot.api.isc.MessageBus;
import com.botwithus.bot.api.isc.SharedState;

import java.util.Set;

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

    /**
     * Returns what the user has told this script to manage, as it is now.
     *
     * <p>The host limits {@link #getOrchestrator()} and
     * {@link #getClientProvider()} to these targets: clients and scripts they
     * do not cover are left out of every list, and a call that would act on one
     * fails with a result saying it is not in this script's targets. An empty
     * set means the script has not been applied to anything yet, so it sees no
     * clients at all.</p>
     *
     * <p>Targets can change while the script runs, and a group target's name
     * changes when the group is renamed, so read this when you need it rather
     * than keeping it from {@link ManagementScript#onStart(ManagementContext)}.</p>
     *
     * <p>A host that does not scope management scripts returns
     * {@link ManagementTarget.WholeHost}, which is what this default does.</p>
     *
     * @return this script's targets; never {@code null}
     */
    default Set<ManagementTarget> targets() {
        return Set.of(new ManagementTarget.WholeHost());
    }
}
