package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.ClientProvider;
import com.botwithus.bot.api.isc.MessageBus;
import com.botwithus.bot.api.isc.SharedState;
import com.botwithus.bot.api.script.ClientOrchestrator;
import com.botwithus.bot.api.script.ManagementContext;
import com.botwithus.bot.api.script.ManagementTarget;

import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Concrete implementation of {@link ManagementContext}.
 */
public class ManagementContextImpl implements ManagementContext {

    private final ClientOrchestrator orchestrator;
    private final ClientProvider clientProvider;
    private final MessageBus messageBus;
    private final SharedState sharedState;
    private final Supplier<Set<ManagementTarget>> targets;

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
        this.orchestrator = orchestrator;
        this.clientProvider = clientProvider;
        this.messageBus = messageBus;
        this.sharedState = sharedState;
        this.targets = Objects.requireNonNull(targets, "targets");
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
    public Set<ManagementTarget> targets() {
        return Set.copyOf(targets.get());
    }
}
