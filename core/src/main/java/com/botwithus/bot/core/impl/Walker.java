package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.Navigation;
import com.botwithus.bot.api.event.WalkArrivedEvent;
import com.botwithus.bot.api.event.WalkCancelledEvent;
import com.botwithus.bot.api.event.WalkFailedEvent;
import com.botwithus.bot.api.model.PathResult;
import com.botwithus.bot.api.model.WalkResult;
import com.botwithus.bot.api.model.WalkStatus;
import com.botwithus.bot.api.model.WorldPathConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Provides the {@link com.botwithus.bot.api.Navigation} contract:
 * blocking walks with timeout, walk control, and path queries.
 *
 * <p>{@link #cleanup()} cancels any active walk; called automatically
 * by the script runtime when a script stops.</p>
 */
public class Walker implements Navigation {

    private static final Logger log = LoggerFactory.getLogger(Walker.class);
    private static final long DEFAULT_TIMEOUT_MS = 300_000; // 5 minutes

    private final GameAPI api;
    private final EventBusImpl eventBus;

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

    // ============================== Region Cache ==============================

    @Override
    public int getRegionCacheSize() {
        return api.getRegionCacheSize();
    }

    @Override
    public void clearRegionCache() {
        api.clearRegionCache();
    }

    // ============================== Cleanup ==============================

    @Override
    public void cleanup() {
        try {
            api.walkCancel();
        } catch (Exception e) {
            log.debug("Walk cancel during cleanup: {}", e.getMessage());
        }
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
