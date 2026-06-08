package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.ClientProvider;
import com.botwithus.bot.api.isc.MessageBus;
import com.botwithus.bot.api.isc.SharedState;
import com.botwithus.bot.api.script.ClientOrchestrator;
import com.botwithus.bot.api.script.ManagementContext;

/**
 * Concrete implementation of {@link ManagementContext}.
 */
public class ManagementContextImpl implements ManagementContext {

    private final ClientOrchestrator orchestrator;
    private final ClientProvider clientProvider;
    private final MessageBus messageBus;
    private final SharedState sharedState;

    public ManagementContextImpl(
            ClientOrchestrator orchestrator,
            ClientProvider clientProvider,
            MessageBus messageBus,
            SharedState sharedState
    ) {
        this.orchestrator = orchestrator;
        this.clientProvider = clientProvider;
        this.messageBus = messageBus;
        this.sharedState = sharedState;
    }

    @Override
    public ClientOrchestrator getOrchestrator() { return orchestrator; }

    @Override
    public ClientProvider getClientProvider() { return clientProvider; }

    @Override
    public MessageBus getMessageBus() { return messageBus; }

    @Override
    public SharedState getSharedState() { return sharedState; }
}
