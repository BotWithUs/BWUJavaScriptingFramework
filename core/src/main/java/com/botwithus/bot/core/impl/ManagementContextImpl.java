package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.ClientProvider;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.isc.MessageBus;
import com.botwithus.bot.api.isc.SharedState;
import com.botwithus.bot.api.script.ClientLauncher;
import com.botwithus.bot.api.script.ClientOrchestrator;
import com.botwithus.bot.api.script.ManagementContext;
import com.botwithus.bot.api.script.ManagementTarget;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Concrete implementation of {@link ManagementContext}.
 */
public class ManagementContextImpl implements ManagementContext {

    /** Answers {@link #configFor(String)} and {@link #configFor(String, String)} for one script. */
    public interface ConfigLookup {

        /** The script's settings for the client on {@code accountUuid}; never {@code null}. */
        ScriptConfig forAccount(String accountUuid);

        /** The script's settings for {@code scriptName} on that client; never {@code null}. */
        ScriptConfig forClientScript(String accountUuid, String scriptName);
    }

    /** What a host that keeps no per-target settings answers, as the API's default does. */
    private static final ConfigLookup NO_TARGET_SETTINGS = new ConfigLookup() {
        @Override
        public ScriptConfig forAccount(String accountUuid) {
            return new ScriptConfig(Map.of());
        }

        @Override
        public ScriptConfig forClientScript(String accountUuid, String scriptName) {
            return new ScriptConfig(Map.of());
        }
    };

    private final ClientOrchestrator orchestrator;
    private final ClientProvider clientProvider;
    private final MessageBus messageBus;
    private final SharedState sharedState;
    private final Supplier<Set<ManagementTarget>> targets;
    private final ConfigLookup configs;
    private final ClientLauncher clientLauncher;

    /** A context whose script manages the whole host. */
    public ManagementContextImpl(
            ClientOrchestrator orchestrator,
            ClientProvider clientProvider,
            MessageBus messageBus,
            SharedState sharedState
    ) {
        this(orchestrator, clientProvider, messageBus, sharedState,
                () -> Set.of(new ManagementTarget.WholeHost()));
    }

    /**
     * @param targets read on every {@link #targets()} call, so a change the
     *                user makes while the script runs shows at once; the
     *                orchestrator and provider should be limited to the same
     *                targets
     */
    public ManagementContextImpl(
            ClientOrchestrator orchestrator,
            ClientProvider clientProvider,
            MessageBus messageBus,
            SharedState sharedState,
            Supplier<Set<ManagementTarget>> targets
    ) {
        this(orchestrator, clientProvider, messageBus, sharedState, targets, NO_TARGET_SETTINGS);
    }

    /**
     * @param targets read on every {@link #targets()} call; see the constructor above
     * @param configs asked on every {@link #configFor(String)} call, so a value
     *                the user changes while the script runs shows at once
     */
    public ManagementContextImpl(
            ClientOrchestrator orchestrator,
            ClientProvider clientProvider,
            MessageBus messageBus,
            SharedState sharedState,
            Supplier<Set<ManagementTarget>> targets,
            ConfigLookup configs
    ) {
        this(orchestrator, clientProvider, messageBus, sharedState, targets, configs, ClientLauncher.unavailable());
    }

    /**
     * @param targets        read on every {@link #targets()} call; see the constructor above
     * @param configs        asked on every {@link #configFor(String)} call
     * @param clientLauncher what {@link #clientLauncher()} returns; it should be
     *                       limited to the same targets
     */
    public ManagementContextImpl(
            ClientOrchestrator orchestrator,
            ClientProvider clientProvider,
            MessageBus messageBus,
            SharedState sharedState,
            Supplier<Set<ManagementTarget>> targets,
            ConfigLookup configs,
            ClientLauncher clientLauncher
    ) {
        this.clientLauncher = Objects.requireNonNull(clientLauncher, "clientLauncher");
        this.orchestrator = orchestrator;
        this.clientProvider = clientProvider;
        this.messageBus = messageBus;
        this.sharedState = sharedState;
        this.targets = Objects.requireNonNull(targets, "targets");
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    @Override
    public ClientOrchestrator getOrchestrator() { return orchestrator; }

    @Override
    public ClientProvider getClientProvider() { return clientProvider; }

    @Override
    public MessageBus getMessageBus() { return messageBus; }

    @Override
    public SharedState getSharedState() { return sharedState; }

    @Override
    public ClientLauncher clientLauncher() {
        return clientLauncher;
    }

    @Override
    public Set<ManagementTarget> targets() {
        return Set.copyOf(targets.get());
    }

    @Override
    public ScriptConfig configFor(String accountUuid) {
        return configs.forAccount(Objects.requireNonNull(accountUuid, "accountUuid"));
    }

    @Override
    public ScriptConfig configFor(String accountUuid, String scriptName) {
        return configs.forClientScript(Objects.requireNonNull(accountUuid, "accountUuid"),
                Objects.requireNonNull(scriptName, "scriptName"));
    }
}
