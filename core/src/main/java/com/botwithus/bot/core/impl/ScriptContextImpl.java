package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.Navigation;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.debug.ScriptContextPublisher;
import com.botwithus.bot.api.event.EventBus;
import com.botwithus.bot.api.isc.MessageBus;
import com.botwithus.bot.api.isc.SharedState;

import java.util.function.BooleanSupplier;

public class ScriptContextImpl implements ScriptContext {

    private final GameAPI gameAPI;
    private final EventBusImpl eventBus;
    /**
     * The bus handed to scripts. Normally {@link #eventBus} itself; a per-script
     * {@link ScopedEventBus} once the runtime has scoped it, so the script's
     * subscriptions can be taken back when it stops. Kept separate from
     * {@link #eventBus} because {@link Walker} and the connection wiring need
     * the concrete {@link EventBusImpl}.
     */
    private final EventBus scriptEventBus;
    private final MessageBus messageBus;
    private final SharedState sharedState;
    private final Navigation navigation;
    private final ScriptContextPublisher scriptContext;
    /** Backs {@link #isStopRequested()}; constant false until the runtime binds it. */
    private final BooleanSupplier stopRequested;

    public ScriptContextImpl(GameAPI gameAPI, EventBusImpl eventBus, MessageBus messageBus,
                             SharedState sharedState, ScriptContextPublisher scriptContext) {
        this(gameAPI, eventBus, eventBus, messageBus, sharedState,
                new Walker(gameAPI, eventBus), scriptContext, () -> false);
    }

    public ScriptContextImpl(GameAPI gameAPI, EventBusImpl eventBus, MessageBus messageBus, SharedState sharedState) {
        this(gameAPI, eventBus, messageBus, sharedState, ScriptContextPublisher.NOOP);
    }

    public ScriptContextImpl(GameAPI gameAPI, EventBusImpl eventBus, MessageBus messageBus) {
        this(gameAPI, eventBus, messageBus, new SharedStateImpl(), ScriptContextPublisher.NOOP);
    }

    private ScriptContextImpl(GameAPI gameAPI, EventBusImpl eventBus, EventBus scriptEventBus,
                              MessageBus messageBus, SharedState sharedState, Navigation navigation,
                              ScriptContextPublisher scriptContext, BooleanSupplier stopRequested) {
        this.gameAPI = gameAPI;
        this.eventBus = eventBus;
        this.scriptEventBus = scriptEventBus;
        this.messageBus = messageBus;
        this.sharedState = sharedState;
        this.navigation = navigation;
        this.scriptContext = scriptContext != null ? scriptContext : ScriptContextPublisher.NOOP;
        this.stopRequested = stopRequested != null ? stopRequested : () -> false;
    }

    /**
     * Returns a copy of this context whose {@link #getScriptContext()} delegates
     * to {@code publisher}. Other fields — including the {@link Navigation} —
     * are shared by reference, preserving the pre-Phase-4 invariant that
     * scripts on one connection see the same Walker.
     */
    public ScriptContextImpl withScriptContext(ScriptContextPublisher publisher) {
        return new ScriptContextImpl(gameAPI, eventBus, scriptEventBus, messageBus, sharedState,
                navigation, publisher != null ? publisher : ScriptContextPublisher.NOOP,
                stopRequested);
    }

    /**
     * Returns a copy of this context that hands the script {@code bus} instead
     * of the connection's shared bus. Used by the runtime to give each script a
     * {@link ScopedEventBus}, so its subscriptions can be removed when it stops
     * rather than outliving it on the event-pump thread.
     */
    public ScriptContextImpl withEventBus(EventBus bus) {
        return new ScriptContextImpl(gameAPI, eventBus, bus != null ? bus : eventBus, messageBus,
                sharedState, navigation, scriptContext, stopRequested);
    }

    /**
     * Returns a copy of this context whose {@link MessageBus} stamps
     * {@code name} as the sender on every outbound message, so one script
     * cannot publish, request or respond under another script's identity.
     * Delivery and subscription semantics are unchanged. A null or blank name
     * leaves the context as-is.
     */
    public ScriptContextImpl withSenderIdentity(String name) {
        if (name == null || name.isBlank()) {
            return this;
        }
        return new ScriptContextImpl(gameAPI, eventBus, scriptEventBus,
                new IdentifiedMessageBus(messageBus, name), sharedState,
                navigation, scriptContext, stopRequested);
    }

    /**
     * Returns a copy of this context whose {@link #isStopRequested()} reads
     * {@code signal}. Bound by the runtime to the owning runner's stop flag so a
     * script can poll it from inside a long {@code onLoop} and exit cleanly.
     */
    public ScriptContextImpl withStopSignal(BooleanSupplier signal) {
        return new ScriptContextImpl(gameAPI, eventBus, scriptEventBus, messageBus, sharedState,
                navigation, scriptContext, signal != null ? signal : () -> false);
    }

    @Override
    public GameAPI getGameAPI() { return gameAPI; }

    @Override
    public EventBus getEventBus() { return scriptEventBus; }

    @Override
    public MessageBus getMessageBus() { return messageBus; }

    @Override
    public SharedState getSharedState() { return sharedState; }

    @Override
    public Navigation getNavigation() { return navigation; }

    @Override
    public ScriptContextPublisher getScriptContext() { return scriptContext; }

    @Override
    public boolean isStopRequested() { return stopRequested.getAsBoolean(); }
}
