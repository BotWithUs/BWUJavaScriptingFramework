package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.event.WalkArrivedEvent;
import com.botwithus.bot.api.model.PathResult;
import com.botwithus.bot.api.model.WalkResult;
import com.botwithus.bot.api.model.WalkStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WalkerTest {

    /**
     * Timeout handed to the blocking walks below. A refusal must return without
     * consuming any of it; the value only bounds how long this test can hang if
     * the fail-fast path regresses.
     */
    private static final long REFUSAL_TIMEOUT_MS = 5_000L;

    /** Generous ceiling on "returned immediately" — orders below the timeout. */
    private static final long FAIL_FAST_BUDGET_MS = 1_000L;

    private GameAPI api;
    private EventBusImpl eventBus;
    private Walker walker;

    @BeforeEach
    void setUp() {
        api = mock(GameAPI.class);
        eventBus = new EventBusImpl();
        walker = new Walker(api, eventBus);
    }

    // ============================== Delegation ==============================

    @Test
    void cancelWalkDelegatesToApi() {
        walker.cancelWalk();
        verify(api).walkCancel();
    }

    @Test
    void getWalkStatusDelegatesToApi() {
        var expected = new WalkStatus("idle", 0, 0, 0, 0, 0, 0, false, true, true);
        when(api.getWalkStatus()).thenReturn(expected);

        assertSame(expected, walker.getWalkStatus());
    }

    @Test
    void isReachableDelegatesToApi() {
        when(api.isReachable(100, 200)).thenReturn(true);
        assertTrue(walker.isReachable(100, 200));
    }

    @Test
    void isReachableWithIterationsDelegatesToApi() {
        when(api.isReachable(100, 200, 512)).thenReturn(false);
        assertFalse(walker.isReachable(100, 200, 512));
    }

    @Test
    void findPathDelegatesToApi() {
        var expected = new PathResult(true, 5, List.of(new int[]{1, 2}));
        when(api.findPath(10, 20)).thenReturn(expected);
        assertSame(expected, walker.findPath(10, 20));
    }

    @Test
    void findPathWithOriginDelegatesToApi() {
        var expected = new PathResult(true, 3, List.of());
        when(api.findPath(1, 2, 3, 4)).thenReturn(expected);
        assertSame(expected, walker.findPath(1, 2, 3, 4));
    }

    @Test
    void findWorldPathDelegatesToApi() {
        var expected = new PathResult(false, 0, List.of());
        when(api.findWorldPath(50, 60)).thenReturn(expected);
        assertSame(expected, walker.findWorldPath(50, 60));
    }

    @Test
    void findWorldPathWithOriginDelegatesToApi() {
        var expected = new PathResult(true, 10, List.of());
        when(api.findWorldPath(1, 2, 3, 4)).thenReturn(expected);
        assertSame(expected, walker.findWorldPath(1, 2, 3, 4));
    }

    @Test
    void regionCacheSizeDelegatesToApi() {
        when(api.getRegionCacheSize()).thenReturn(42);
        assertEquals(42, walker.getRegionCacheSize());
    }

    @Test
    void clearRegionCacheDelegatesToApi() {
        walker.clearRegionCache();
        verify(api).clearRegionCache();
    }

    // ============================== Refusal ==============================

    @Test
    void blockingWalkFailsFastWhenTheRequestIsRefused() {
        // 0 before the request, 1 after: this request was the one refused.
        when(api.walkRefusalCount()).thenReturn(0L, 1L);

        long startedAt = System.nanoTime();
        WalkResult result = walker.walkTo(100, 200, REFUSAL_TIMEOUT_MS);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        assertEquals(WalkResult.FAILED, result);
        assertTrue(elapsedMs < FAIL_FAST_BUDGET_MS,
                "a refusal must return at once, not park on a terminal event that never comes; took "
                        + elapsedMs + "ms");
        verify(api).walkToAsync(100, 200);
    }

    @Test
    void blockingWalkIgnoresARefusalItDidNotCause() {
        // A refusal is on record from an earlier request — the level still
        // reads refused_busy — but the count does not move across this one, so
        // this walk really did start and must be allowed to finish.
        when(api.walkRefusalCount()).thenReturn(3L);
        when(api.getWalkStatus()).thenReturn(new WalkStatus(
                WalkState.REFUSED_BUSY.wireName(), 0, 0, 0, 0, 0, 0, false, true, true));
        doAnswer(invocation -> {
            eventBus.publish(new WalkArrivedEvent(100, 200));
            return null;
        }).when(api).walkToAsync(100, 200);

        assertEquals(WalkResult.ARRIVED, walker.walkTo(100, 200, REFUSAL_TIMEOUT_MS));
    }

    @Test
    void refusalIsDetectedWithoutReadingTheWalkStatusLevel() {
        // getWalkStatus reports "walking" to a host or management caller
        // whenever any lease is active, so inferring a refusal from it cannot
        // work for those callers. The count is the only thing consulted.
        when(api.walkRefusalCount()).thenReturn(0L, 1L);

        assertEquals(WalkResult.FAILED, walker.walkTo(100, 200, REFUSAL_TIMEOUT_MS));
        verify(api, never()).getWalkStatus();
    }

    // ============================== Cleanup ==============================

    @Test
    void cleanupCancelsActiveWalk() {
        walker.cleanup();
        verify(api).walkCancel();
    }

    @Test
    void cleanupIsIdempotent() {
        walker.cleanup();
        walker.cleanup();
        verify(api, times(2)).walkCancel();
    }
}
