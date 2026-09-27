package com.botwithus.bot.api.script;

import com.botwithus.bot.api.ClientProvider;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.isc.MessageBus;
import com.botwithus.bot.api.isc.SharedState;

import java.util.Map;
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

    /**
     * Returns this script's settings as they apply to the client on account
     * {@code accountUuid}.
     *
     * <p>The user can give a management script different values for different
     * {@linkplain #targets() targets}. Each value comes from the first of these
     * that sets it:</p>
     * <ol>
     *   <li>a {@link ManagementTarget.ClientScript} target on that account;</li>
     *   <li>a {@link ManagementTarget.Group} target the client is a member of,
     *       the oldest group first;</li>
     *   <li>the defaults: the config
     *       {@link ManagementScript#onConfigUpdate(ScriptConfig)} receives.</li>
     * </ol>
     *
     * <p>When the script targets more than one script on the same client, their
     * values are taken in the order the targets were added; use
     * {@link #configFor(String, String)} to name the one you mean. A client the
     * targets do not cover gets the defaults, as does every client of a script
     * that manages the whole host.</p>
     *
     * <p>{@code onConfigUpdate} still receives only the defaults, and is not
     * called when a target's own values change. Those can change at any time,
     * so read this when you act on a client rather than keeping it.</p>
     *
     * <p>A host that keeps no per-target settings returns an empty config, so
     * every getter returns the fallback it is given; this default does that.</p>
     *
     * @param accountUuid the client's account UUID, as
     *                    {@link ManagementTarget.ClientScript#accountUuid()} gives it
     * @return the merged config; never {@code null}
     */
    default ScriptConfig configFor(String accountUuid) {
        return new ScriptConfig(Map.of());
    }

    /**
     * Returns this script's settings as they apply to the script named
     * {@code scriptName} on the client on account {@code accountUuid}: the same
     * order as {@link #configFor(String)}, with only that script's own values in
     * the first place.
     *
     * <p>This default returns {@link #configFor(String)}.</p>
     *
     * @param accountUuid the client's account UUID
     * @param scriptName  the client script's manifest name
     * @return the merged config; never {@code null}
     */
    default ScriptConfig configFor(String accountUuid, String scriptName) {
        return configFor(accountUuid);
    }
}
