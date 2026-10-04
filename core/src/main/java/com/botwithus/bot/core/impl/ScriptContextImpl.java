package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.Navigation;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.debug.ScriptContextPublisher;
import com.botwithus.bot.api.event.EventBus;
import com.botwithus.bot.api.isc.MessageBus;
import com.botwithus.bot.api.isc.SharedState;

import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public class ScriptContextImpl implements ScriptContext {

    /** Stop hook for contexts the runtime has not bound; matches the {@link ScriptContext#stopSelf} no-op. */
    private static final Runnable NO_STOP_CALLBACK = () -> { };
    /** Name source for contexts the host has not bound to an account reader; matches the interface default. */
    private static final Supplier<Optional<String>> NO_DISPLAY_NAME = Optional::empty;

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
    /** Backs {@link #stopSelf()}; a no-op until the runtime binds it. */
    private final Runnable stopCallback;
    /** Backs {@link #getDisplayName()}; empty until the host binds it. Never blocks on the pipe. */
    private final Supplier<Optional<String>> displayName;

    public ScriptContextImpl(GameAPI gameAPI, EventBusImpl eventBus, MessageBus messageBus,
                             SharedState sharedState, ScriptContextPublisher scriptContext) {
        this(gameAPI, eventBus, eventBus, messageBus, sharedState,
                new Walker(gameAPI, eventBus), scriptContext, () -> false, NO_STOP_CALLBACK,
                NO_DISPLAY_NAME);
    }

    public ScriptContextImpl(GameAPI gameAPI, EventBusImpl eventBus, MessageBus messageBus, SharedState sharedState) {
        this(gameAPI, eventBus, messageBus, sharedState, ScriptContextPublisher.NOOP);
    }

    public ScriptContextImpl(GameAPI gameAPI, EventBusImpl eventBus, MessageBus messageBus) {
        this(gameAPI, eventBus, messageBus, new SharedStateImpl(), ScriptContextPublisher.NOOP);
    }

    private ScriptContextImpl(GameAPI gameAPI, EventBusImpl eventBus, EventBus scriptEventBus,
                              MessageBus messageBus, SharedState sharedState, Navigation navigation,
                              ScriptContextPublisher scriptContext, BooleanSupplier stopRequested,
                              Runnable stopCallback, Supplier<Optional<String>> displayName) {
        this.gameAPI = gameAPI;
        this.eventBus = eventBus;
        this.scriptEventBus = scriptEventBus;
        this.messageBus = messageBus;
        this.sharedState = sharedState;
        this.navigation = navigation;
        this.scriptContext = scriptContext != null ? scriptContext : ScriptContextPublisher.NOOP;
        this.stopRequested = stopRequested != null ? stopRequested : () -> false;
        this.stopCallback = stopCallback != null ? stopCallback : NO_STOP_CALLBACK;
        this.displayName = displayName != null ? displayName : NO_DISPLAY_NAME;
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
                stopRequested, stopCallback, displayName);
    }

    /**
     * Returns a copy of this context that hands the script {@code bus} instead
     * of the connection's shared bus. Used by the runtime to give each script a
     * {@link ScopedEventBus}, so its subscriptions can be removed when it stops
     * rather than outliving it on the event-pump thread.
     */
    public ScriptContextImpl withEventBus(EventBus bus) {
        return new ScriptContextImpl(gameAPI, eventBus, bus != null ? bus : eventBus, messageBus,
                sharedState, navigation, scriptContext, stopRequested, stopCallback, displayName);
    }

    /**
     * Returns a copy of this context holding the message bus a running script
     * should see: {@code scoped}, so its ISC handlers can be taken back when it
     * stops rather than outliving it on a virtual thread, under an identity stamp
     * that makes {@code name} the sender subscribers observe — so one script
     * cannot publish, request or respond under another script's identity.
     * Delivery and subscription semantics are otherwise unchanged.
     *
     * <p>The two layers are composed here rather than by two chained withers
     * because their order is load-bearing and nothing else would enforce it: an
     * identity stamp applied <em>underneath</em> the scope is silently discarded
     * the moment the scope replaces the bus, and the spoofing protection goes with
     * it. A null bus or a null/blank name leaves that layer off.</p>
     */
    public ScriptContextImpl withScriptMessageBus(MessageBus scoped, String name) {
        MessageBus bus = scoped != null ? scoped : messageBus;
        MessageBus identified = name == null || name.isBlank()
                ? bus
                : new IdentifiedMessageBus(bus, name);
        return new ScriptContextImpl(gameAPI, eventBus, scriptEventBus, identified, sharedState,
                navigation, scriptContext, stopRequested, stopCallback, displayName);
    }

    /**
     * Returns a copy of this context whose {@link #isStopRequested()} reads
     * {@code signal}. Bound by the runtime to the owning runner's stop flag so a
     * script can poll it from inside a long {@code onLoop} and exit cleanly.
     */
    public ScriptContextImpl withStopSignal(BooleanSupplier signal) {
        return new ScriptContextImpl(gameAPI, eventBus, scriptEventBus, messageBus, sharedState,
                navigation, scriptContext, signal != null ? signal : () -> false, stopCallback,
                displayName);
    }

    /**
     * Returns a copy of this context whose {@link #stopSelf()} runs
     * {@code callback}. Bound by the runtime to the same stop path a user Stop
     * takes, so a self-stop sets the flag {@link #withStopSignal} reads and is
     * observed exactly like a user stop.
     */
    public ScriptContextImpl withStopCallback(Runnable callback) {
        return new ScriptContextImpl(gameAPI, eventBus, scriptEventBus, messageBus, sharedState,
                navigation, scriptContext, stopRequested,
                callback != null ? callback : NO_STOP_CALLBACK, displayName);
    }

    /**
     * Returns a copy of this context whose {@link #getDisplayName()} reads
     * {@code source}. Bound by the host to its connection's account reader, so
     * every script on one connection sees the same character name. The source is
     * called from script threads and must not block on the pipe; a {@code null}
     * one reports no name.
     */
    public ScriptContextImpl withDisplayName(Supplier<Optional<String>> source) {
        return new ScriptContextImpl(gameAPI, eventBus, scriptEventBus, messageBus, sharedState,
                navigation, scriptContext, stopRequested, stopCallback,
                source != null ? source : NO_DISPLAY_NAME);
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

    @Override
    public void stopSelf() { stopCallback.run(); }

    @Override
    public Optional<String> getDisplayName() { return displayName.get(); }
}
