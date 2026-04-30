package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.Client;
import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.event.EventBus;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.core.impl.snapshot.GameSnapshotImpl;
import com.botwithus.bot.core.shm.SharedRegion;

import java.util.function.BooleanSupplier;

public class ClientImpl implements Client {

    private final String name;
    private final GameAPI gameAPI;
    private final EventBus eventBus;
    private final BooleanSupplier connectedCheck;
    private final SharedRegion region;

    /**
     * Constructs a fully-bound client. {@code region} is the shared-memory
     * binding from which {@link #snapshot()} reads; lifetime stays with the
     * caller (typically the event pump that opened it).
     */
    public ClientImpl(String name, GameAPI gameAPI, EventBus eventBus,
                      BooleanSupplier connectedCheck, SharedRegion region) {
        this.name = name;
        this.gameAPI = gameAPI;
        this.eventBus = eventBus;
        this.connectedCheck = connectedCheck;
        this.region = region;
    }

    /**
     * Constructs a client without a shared-memory binding — {@link #snapshot()}
     * returns {@code null}. Useful for tests and pre-pump construction.
     */
    public ClientImpl(String name, GameAPI gameAPI, EventBus eventBus,
                      BooleanSupplier connectedCheck) {
        this(name, gameAPI, eventBus, connectedCheck, null);
    }

    @Override
    public String getName() { return name; }

    @Override
    public GameAPI getGameAPI() { return gameAPI; }

    @Override
    public EventBus getEventBus() { return eventBus; }

    @Override
    public boolean isConnected() { return connectedCheck.getAsBoolean(); }

    @Override
    public GameSnapshot snapshot() {
        return region == null ? null : new GameSnapshotImpl(region.snapshot());
    }
}
