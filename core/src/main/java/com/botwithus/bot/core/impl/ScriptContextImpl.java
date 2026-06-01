package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.Navigation;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.event.EventBus;
import com.botwithus.bot.api.isc.MessageBus;
import com.botwithus.bot.api.isc.SharedState;

public class ScriptContextImpl implements ScriptContext {

    private final GameAPI gameAPI;
    private final EventBus eventBus;
    private final MessageBus messageBus;
    private final SharedState sharedState;
    private final Navigation navigation;

    public ScriptContextImpl(GameAPI gameAPI, EventBusImpl eventBus, MessageBus messageBus, SharedState sharedState) {
        this.gameAPI = gameAPI;
        this.eventBus = eventBus;
        this.messageBus = messageBus;
        this.sharedState = sharedState;
        this.navigation = new Walker(gameAPI, eventBus);
    }

    public ScriptContextImpl(GameAPI gameAPI, EventBusImpl eventBus, MessageBus messageBus) {
        this(gameAPI, eventBus, messageBus, new SharedStateImpl());
    }

    @Override
    public GameAPI getGameAPI() { return gameAPI; }

    @Override
    public EventBus getEventBus() { return eventBus; }

    @Override
    public MessageBus getMessageBus() { return messageBus; }

    @Override
    public SharedState getSharedState() { return sharedState; }

    @Override
    public Navigation getNavigation() { return navigation; }
}
