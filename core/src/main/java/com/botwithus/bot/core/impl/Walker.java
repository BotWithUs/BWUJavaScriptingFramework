package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.event.*;
import com.botwithus.bot.api.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Provides the full {@link com.botwithus.bot.api.Navigation} contract:
 * blocking walks, nav link management, teleport registration,
 * and automatic cleanup of every modification.
 *
 * <p>Every {@code add*} / {@code registerTeleports} call is recorded
 * in a cleanup list. {@link #cleanup()} replays that list in reverse,
 * cancels any active walk, and resets the tracking state.</p>
 */
public class Walker implements com.botwithus.bot.api.Navigation {

    private static final Logger log = LoggerFactory.getLogger(Walker.class);
    private static final long DEFAULT_TIMEOUT_MS = 300_000; // 5 minutes

    private final GameAPI api;
    private final EventBusImpl eventBus;

    /** Cleanup actions recorded in insertion order; replayed in reverse on cleanup(). */
    private final List<Runnable> cleanupActions = new ArrayList<>();
    private boolean registeredTeleports;

    public Walker(GameAPI api, EventBusImpl eventBus) {
        this.api = api;
        this.eventBus = eventBus;
    }

    // ============================== Blocking Walks ==============================

    @Override
    public WalkResult walkTo(int x, int y) {
        return walkTo(x, y, DEFAULT_TIMEOUT_MS);
    }

    @Override
    public WalkResult walkTo(int x, int y, long timeoutMs) {
        return doBlockingWalk(() -> api.walkToAsync(x, y), timeoutMs);
    }

    @Override
    public WalkResult walkWorldPath(int x, int y, int plane) {
        return walkWorldPath(x, y, plane, DEFAULT_TIMEOUT_MS);
    }

    @Override
    public WalkResult walkWorldPath(int x, int y, int plane, long timeoutMs) {
        return doBlockingWalk(() -> api.walkWorldPathAsync(x, y, plane), timeoutMs);
    }

    @Override
    public WalkResult walkWorldPath(int x, int y, int plane, boolean exactDestTile, WorldPathConfig config) {
        return walkWorldPath(x, y, plane, exactDestTile, config, DEFAULT_TIMEOUT_MS);
    }

    @Override
    public WalkResult walkWorldPath(int x, int y, int plane, boolean exactDestTile, WorldPathConfig config, long timeoutMs) {
        return doBlockingWalk(() -> api.walkWorldPathAsync(x, y, plane, exactDestTile, config), timeoutMs);
    }

    // ============================== Walk Control ==============================

    @Override
    public void cancelWalk() {
        api.walkCancel();
    }

    @Override
    public WalkStatus getWalkStatus() {
        return api.getWalkStatus();
    }

    // ============================== Path Queries ==============================

    @Override
    public boolean isReachable(int x, int y) {
        return api.isReachable(x, y);
    }

    @Override
    public boolean isReachable(int x, int y, int maxIterations) {
        return api.isReachable(x, y, maxIterations);
    }

    @Override
    public PathResult findPath(int toX, int toY) {
        return api.findPath(toX, toY);
    }

    @Override
    public PathResult findPath(int fromX, int fromY, int toX, int toY) {
        return api.findPath(fromX, fromY, toX, toY);
    }

    @Override
    public PathResult findWorldPath(int toX, int toY) {
        return api.findWorldPath(toX, toY);
    }

    @Override
    public PathResult findWorldPath(int fromX, int fromY, int toX, int toY) {
        return api.findWorldPath(fromX, fromY, toX, toY);
    }

    // ============================== Nav Link Management ==============================

    @Override
    public void addTransport(NavTransport transport) {
        api.navAddTransport(transport);
        cleanupActions.add(() -> api.navRemoveTransport(
                transport.objectId(), transport.x(), transport.y(), transport.plane()));
    }

    @Override
    public void removeTransport(int objectId, int x, int y, int plane) {
        api.navRemoveTransport(objectId, x, y, plane);
    }

    @Override
    public List<NavTransport> listTransports() {
        return api.navListTransports();
    }

    @Override
    public void addDoor(NavDoor door) {
        api.navAddDoor(door);
        cleanupActions.add(() -> api.navRemoveDoor(
                door.objectId(), door.x(), door.y(), door.plane()));
    }

    @Override
    public void removeDoor(int objectId, int x, int y, int plane) {
        api.navRemoveDoor(objectId, x, y, plane);
    }

    @Override
    public List<NavDoor> listDoors() {
        return api.navListDoors();
    }

    @Override
    public void addShortcut(NavShortcut shortcut) {
        api.navAddShortcut(shortcut);
        cleanupActions.add(() -> api.navRemoveShortcut(
                shortcut.objectId(), shortcut.x(), shortcut.y(), shortcut.plane()));
    }

    @Override
    public void removeShortcut(int objectId, int x, int y, int plane) {
        api.navRemoveShortcut(objectId, x, y, plane);
    }

    @Override
    public void addPlaneTransition(NavPlaneTransition transition) {
        api.navAddPlaneTransition(transition);
        cleanupActions.add(() -> api.navRemovePlaneTransition(
                transition.objectId(), transition.x(), transition.y(), transition.plane()));
    }

    @Override
    public void removePlaneTransition(int objectId, int x, int y, int plane) {
        api.navRemovePlaneTransition(objectId, x, y, plane);
    }

    @Override
    public void addClimbover(NavClimbover climbover) {
        api.navAddClimbover(climbover);
        cleanupActions.add(() -> api.navRemoveClimbover(
                climbover.objectId(), climbover.x(), climbover.y(), climbover.plane()));
    }

    @Override
    public void removeClimbover(int objectId, int x, int y, int plane) {
        api.navRemoveClimbover(objectId, x, y, plane);
    }

    // ============================== Batch Link Operations ==============================

    @Override
    public int loadLinksJson(List<NavTransport> links) {
        return api.navLoadJson(links);
    }

    @Override
    public void saveLinks(String path) {
        api.navSaveLinks(path);
    }

    @Override
    public int loadLinks(String path) {
        return api.navLoadLinks(path);
    }

    // ============================== Teleports ==============================

    @Override
    public int registerTeleports(String json, String format) {
        int added = api.navRegisterTeleports(json, format);
        if (added > 0) {
            registeredTeleports = true;
        }
        return added;
    }

    @Override
    public int clearScriptTeleports() {
        registeredTeleports = false;
        return api.navClearScriptTeleports();
    }

    @Override
    public List<NavTeleport> listTeleports(boolean scriptOnly) {
        return api.navListTeleports(scriptOnly);
    }

    // ============================== Region Cache ==============================

    @Override
    public int getRegionCacheSize() {
        return api.getRegionCacheSize();
    }

    @Override
    public void clearRegionCache() {
        api.clearRegionCache();
    }

    // ============================== Stats ==============================

    @Override
    public NavStats getNavStats() {
        return api.navGetStats();
    }

    // ============================== Cleanup ==============================

    @Override
    public void cleanup() {
        // Cancel any active walk first
        try {
            api.walkCancel();
        } catch (Exception e) {
            log.debug("Walk cancel during cleanup: {}", e.getMessage());
        }

        // Clear script-registered teleports
        if (registeredTeleports) {
            try {
                api.navClearScriptTeleports();
                registeredTeleports = false;
            } catch (Exception e) {
                log.warn("Failed to clear script teleports during cleanup: {}", e.getMessage());
            }
        }

        // Remove added links in reverse order
        for (int i = cleanupActions.size() - 1; i >= 0; i--) {
            try {
                cleanupActions.get(i).run();
            } catch (Exception e) {
                log.debug("Cleanup action failed (link may already be removed): {}", e.getMessage());
            }
        }
        cleanupActions.clear();

        log.debug("Navigation cleanup complete");
    }

    // ============================== Internal ==============================

    private WalkResult doBlockingWalk(Runnable startWalk, long timeoutMs) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<WalkResult> result = new AtomicReference<>();

        Consumer<WalkArrivedEvent> arrivedListener = e -> {
            result.set(WalkResult.ARRIVED);
            latch.countDown();
        };
        Consumer<WalkCancelledEvent> cancelledListener = e -> {
            result.set(WalkResult.CANCELLED);
            latch.countDown();
        };
        Consumer<WalkFailedEvent> failedListener = e -> {
            result.set(WalkResult.FAILED);
            latch.countDown();
        };

        eventBus.subscribe(WalkArrivedEvent.class, arrivedListener);
        eventBus.subscribe(WalkCancelledEvent.class, cancelledListener);
        eventBus.subscribe(WalkFailedEvent.class, failedListener);

        try {
            startWalk.run();

            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                log.warn("Walk timed out after {}ms", timeoutMs);
                api.walkCancel();
                return WalkResult.TIMEOUT;
            }

            return result.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Walk interrupted");
            api.walkCancel();
            return WalkResult.TIMEOUT;
        } finally {
            eventBus.unsubscribe(WalkArrivedEvent.class, arrivedListener);
            eventBus.unsubscribe(WalkCancelledEvent.class, cancelledListener);
            eventBus.unsubscribe(WalkFailedEvent.class, failedListener);
        }
    }
}
