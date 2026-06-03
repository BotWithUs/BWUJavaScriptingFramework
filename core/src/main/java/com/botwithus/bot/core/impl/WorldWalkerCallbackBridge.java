package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.snapshot.Location;
import com.botwithus.bot.api.util.Interfaces;
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
    public int interact(int objectId, WwTile tile, int optionIndex) {
        if (optionIndex < 0 || optionIndex + 1 >= ActionTypes.OBJECT_OPTIONS.length) {
            log.warn("interact: option index {} out of range for loc {}", optionIndex, objectId);
            return 0;
        }
        // The NXT engine's object DoAction takes (locTypeId, worldX, worldY) —
        // the same shape a manual click emits. We deliberately do NOT use the
        // scene interact handle: doors and stairs are published as
        // COMBINED_LOCATION_SECTIONs, which always carry interactId == -1, so a
        // handle lookup can never resolve them. We only need the loc's true
        // tile, which sections do carry. The transition's origin can sit one
        // tile off the loc (reverse-direction door hops), so we snap to the
        // nearest matching loc within Chebyshev radius 1.
        WwTile locTile = resolveLocTile(objectId, tile);
        if (locTile == null) {
            // The baked transition names the CLOSED loc (the world-map door, or
            // the stairs/ladder object). When that loc is absent from the live
            // scene at the interact tile the obstacle is no longer there to act
            // on: an open door is a different loc id, so a door we've already
            // opened (or that spawned open) simply isn't found. Treat it as
            // already-traversable and skip the interact — the executor advances
            // to the next step, whose walk routes straight through the open
            // doorway. (A plane-change loc can't be "open"; if a stairs loc were
            // ever missing the following different-plane walk would stall and
            // trigger a re-plan, which is the correct failure mode, not a clip.)
            log.info("interact: loc {} absent at ({},{},{}); assuming already open/removed, skipping",
                    objectId, tile.x(), tile.y(), tile.plane());
            return 0;
        }
        int actionId = ActionTypes.OBJECT_OPTIONS[optionIndex + 1];
        api.queueAction(new GameAction(actionId, objectId, locTile.x(), locTile.y()));
        return 1;
    }

    @Override
    public void runChainStep(int interfaceId, int componentId, int optionId) {
        // One Click step of a transition's chain (e.g. a lodestone-network or
        // spell teleport). The executor has already waited for the interface to
        // be open, so we just issue the component interaction. Same GameAction
        // shape as ComponentNode.interact: option in param1, packed
        // (iface<<16)|comp hash in param2, -1 (no sub-slot) in param3.
        int hash = Interfaces.componentHash(interfaceId, componentId);
        log.debug("runChainStep: click iface={} comp={} option={} (hash={})",
                interfaceId, componentId, optionId, hash);
        api.queueAction(new GameAction(ActionTypes.COMPONENT, optionId, hash, -1));
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

    private WwTile resolveLocTile(int objectId, WwTile tile) {
        GameSnapshot snap = snapshotSource.get();
        if (snap == null) {
            return null;
        }
        // Match by type + plane within one tile of the interaction origin. Doors
        // and stairs arrive as combined-location sections (interactId == -1), so
        // we deliberately do NOT filter on interactId or the section flag — only
        // the deleted flag, which marks a despawned loc. Prefer the exact origin
        // tile, then the nearest. We return the loc's own tile so the
        // (typeId, worldX, worldY) action targets where the loc actually sits,
        // even when approached from the far side.
        return snap.locations().stream()
                .filter(loc -> loc.typeId() == objectId
                        && loc.plane() == tile.plane()
                        && chebyshev(loc, tile) <= 1
                        && !loc.isDeleted())
                .min(Comparator.comparingInt(loc -> chebyshev(loc, tile)))
                .map(loc -> new WwTile(loc.tileX(), loc.tileY(), loc.plane()))
                .orElse(null);
    }
}
