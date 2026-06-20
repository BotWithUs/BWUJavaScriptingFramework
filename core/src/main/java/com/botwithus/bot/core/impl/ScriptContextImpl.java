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
    /**
     * Optional self-stop hook wired by the {@link com.botwithus.bot.core.runtime.ScriptRunner}
     * via {@link #withStopCallback}. {@code null} when nothing is wired — that
     * matches the {@link ScriptContext#stopSelf} default no-op contract.
     */
    private final Runnable stopCallback;

    public ScriptContextImpl(GameAPI gameAPI, EventBusImpl eventBus, MessageBus messageBus,
                             SharedState sharedState, ScriptContextPublisher scriptContext) {
        this(gameAPI, eventBus, messageBus, sharedState, new Walker(gameAPI, eventBus), scriptContext, null);
    }

    public ScriptContextImpl(GameAPI gameAPI, EventBusImpl eventBus, MessageBus messageBus, SharedState sharedState) {
        this(gameAPI, eventBus, messageBus, sharedState, ScriptContextPublisher.NOOP);
    }

    public ScriptContextImpl(GameAPI gameAPI, EventBusImpl eventBus, MessageBus messageBus) {
        this(gameAPI, eventBus, messageBus, new SharedStateImpl(), ScriptContextPublisher.NOOP);
    }

    private ScriptContextImpl(GameAPI gameAPI, EventBusImpl eventBus, MessageBus messageBus,
                              SharedState sharedState, Navigation navigation,
                              ScriptContextPublisher scriptContext,
                              Runnable stopCallback) {
        this.gameAPI = gameAPI;
        this.eventBus = eventBus;
        this.messageBus = messageBus;
        this.sharedState = sharedState;
        this.navigation = navigation;
        this.scriptContext = scriptContext != null ? scriptContext : ScriptContextPublisher.NOOP;
        this.stopCallback = stopCallback;
    }

    /**
     * Returns a copy of this context whose {@link #getScriptContext()} delegates
     * to {@code publisher}. Other fields — including the {@link Navigation} and
     * any wired {@code stopCallback} — are shared by reference, preserving the
     * pre-Phase-4 invariant that scripts on one connection see the same Walker.
     */
    public ScriptContextImpl withScriptContext(ScriptContextPublisher publisher) {
        return new ScriptContextImpl(gameAPI, eventBus, messageBus, sharedState, navigation,
                publisher != null ? publisher : ScriptContextPublisher.NOOP, stopCallback);
    }

    /**
     * Returns a copy of this context wired to invoke {@code callback} on
     * {@link #stopSelf()}. Mirrors {@link #withScriptContext}'s immutability
     * pattern — every other field is shared by reference. The runner calls
     * this once per script, passing its own {@code stop} method as the
     * callback.
     */
    public ScriptContextImpl withStopCallback(Runnable callback) {
        return new ScriptContextImpl(gameAPI, eventBus, messageBus, sharedState, navigation,
                scriptContext, callback);
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

    @Override
    public void stopSelf() {
        if (stopCallback != null) {
            stopCallback.run();
        }
    }
}
