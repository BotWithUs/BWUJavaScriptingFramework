package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.snapshot.Location;
import com.botwithus.bot.core.worldwalker.CapabilitySnapshot;
import com.botwithus.bot.core.worldwalker.WorldWalkerException;
import com.botwithus.bot.core.worldwalker.WwCallbacks;
import com.botwithus.bot.core.worldwalker.WwEvent;
import com.botwithus.bot.core.worldwalker.WwTile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class WorldWalkerCallbackBridge implements WwCallbacks {

    private static final Logger log = LoggerFactory.getLogger(WorldWalkerCallbackBridge.class);

    private static final long TICK_MS = 600L;

    private final GameAPI api;
    private final Supplier<GameSnapshot> snapshotSource;
    private final AtomicBoolean cancel;
    private final Consumer<WwEvent> eventSink;

    WorldWalkerCallbackBridge(GameAPI api,
                              Supplier<GameSnapshot> snapshotSource,
                              AtomicBoolean cancel,
                              Consumer<WwEvent> eventSink) {
        this.api = Objects.requireNonNull(api, "api");
        this.snapshotSource = Objects.requireNonNull(snapshotSource, "snapshotSource");
        this.cancel = Objects.requireNonNull(cancel, "cancel");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
    }

    @Override
    public WwTile readPosition() {
        LocalPlayer lp = currentPlayer();
        if (lp == null) {
            return new WwTile(0, 0, 0);
        }
        return new WwTile(lp.tileX(), lp.tileY(), lp.plane());
    }

    @Override
    public CapabilitySnapshot readCapability() {
        return CapabilitySnapshot.empty();
    }

    @Override
    public int readVarbit(int id) {
        try {
            return api.getVarbit(id);
        } catch (RuntimeException e) {
            log.debug("readVarbit({}) failed: {}", id, e.toString());
            return 0;
        }
    }

    @Override
    public boolean isInterfaceOpen(int interfaceId) {
        try {
            return !api.getInterfaceTree(interfaceId, 0).isEmpty();
        } catch (RuntimeException e) {
            log.debug("isInterfaceOpen({}) failed: {}", interfaceId, e.toString());
            return false;
        }
    }

    @Override
    public void walkTo(WwTile target) {
        api.queueAction(new GameAction(ActionTypes.WALK, 1, target.x(), target.y()));
    }

    @Override
    public void interact(int objectId, WwTile tile, int optionIndex) {
        if (optionIndex < 0 || optionIndex + 1 >= ActionTypes.OBJECT_OPTIONS.length) {
            log.warn("interact: option index {} out of range for loc {}", optionIndex, objectId);
            return;
        }
        int handle = resolveLocHandle(objectId, tile);
        if (handle == 0) {
            log.warn("interact: no scene loc {} at ({},{},{})",
                    objectId, tile.x(), tile.y(), tile.plane());
            return;
        }
        int actionId = ActionTypes.OBJECT_OPTIONS[optionIndex + 1];
        api.queueAction(new GameAction(actionId, handle, 0, 0));
    }

    @Override
    public void runChainStep(int chainIndex, int stepIndex) {
        log.warn("runChainStep({}, {}) — executor refactor not yet shipped; skipping",
                chainIndex, stepIndex);
    }

    @Override
    public void sleepTicks(int ticks) {
        if (ticks <= 0) {
            return;
        }
        try {
            Thread.sleep(ticks * TICK_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancel.set(true);
            throw new WorldWalkerException("sleepTicks interrupted", e);
        }
    }

    @Override
    public boolean shouldCancel() {
        return cancel.get();
    }

    @Override
    public void onEvent(WwEvent event) {
        eventSink.accept(event);
    }

    private LocalPlayer currentPlayer() {
        GameSnapshot snap = snapshotSource.get();
        return snap == null ? null : snap.self();
    }

    private static int chebyshev(Location loc, WwTile tile) {
        return Math.max(Math.abs(loc.tileX() - tile.x()), Math.abs(loc.tileY() - tile.y()));
    }

    private int resolveLocHandle(int objectId, WwTile tile) {
        GameSnapshot snap = snapshotSource.get();
        if (snap == null) {
            return 0;
        }
        // The transition's origin is the tile the walker stands on; a door loc
        // sits on one tile but is approached from the adjacent side, so the loc
        // can be one tile off the origin. Accept any matching loc within Chebyshev
        // radius 1, preferring the exact origin tile then the nearest.
        return snap.locations().stream()
                .filter(loc -> loc.typeId() == objectId
                        && loc.plane() == tile.plane()
                        && chebyshev(loc, tile) <= 1
                        && !loc.isCombinedSection()
                        && !loc.isDeleted()
                        && loc.interactId() > 0)
                .min(Comparator.comparingInt(loc -> chebyshev(loc, tile)))
                .map(Location::interactId)
                .orElse(0);
    }
}
