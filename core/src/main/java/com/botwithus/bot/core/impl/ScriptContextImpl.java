package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.Navigation;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.debug.ScriptContextPublisher;
import com.botwithus.bot.api.event.EventBus;
import com.botwithus.bot.api.isc.MessageBus;
import com.botwithus.bot.api.isc.SharedState;

public class ScriptContextImpl implements ScriptContext {

    private final GameAPI gameAPI;
    private final EventBusImpl eventBus;
    private final MessageBus messageBus;
    private final SharedState sharedState;
    private final Navigation navigation;
    private final ScriptContextPublisher scriptContext;

    public ScriptContextImpl(GameAPI gameAPI, EventBusImpl eventBus, MessageBus messageBus,
                             SharedState sharedState, ScriptContextPublisher scriptContext) {
        this(gameAPI, eventBus, messageBus, sharedState, new Walker(gameAPI, eventBus), scriptContext);
    }

    public ScriptContextImpl(GameAPI gameAPI, EventBusImpl eventBus, MessageBus messageBus, SharedState sharedState) {
        this(gameAPI, eventBus, messageBus, sharedState, ScriptContextPublisher.NOOP);
    }

    public ScriptContextImpl(GameAPI gameAPI, EventBusImpl eventBus, MessageBus messageBus) {
        this(gameAPI, eventBus, messageBus, new SharedStateImpl(), ScriptContextPublisher.NOOP);
    }

    private ScriptContextImpl(GameAPI gameAPI, EventBusImpl eventBus, MessageBus messageBus,
                              SharedState sharedState, Navigation navigation,
                              ScriptContextPublisher scriptContext) {
        this.gameAPI = gameAPI;
        this.eventBus = eventBus;
        this.messageBus = messageBus;
        this.sharedState = sharedState;
        this.navigation = navigation;
        this.scriptContext = scriptContext != null ? scriptContext : ScriptContextPublisher.NOOP;
    }

    /**
     * Returns a copy of this context whose {@link #getScriptContext()} delegates
     * to {@code publisher}. Other fields — including the {@link Navigation} —
     * are shared by reference, preserving the pre-Phase-4 invariant that
     * scripts on one connection see the same Walker.
     */
    public ScriptContextImpl withScriptContext(ScriptContextPublisher publisher) {
        return new ScriptContextImpl(gameAPI, eventBus, messageBus, sharedState, navigation,
                publisher != null ? publisher : ScriptContextPublisher.NOOP);
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

    @Override
    public ScriptContextPublisher getScriptContext() { return scriptContext; }
}
