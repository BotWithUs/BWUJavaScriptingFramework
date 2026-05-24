package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.PathResult;
import com.botwithus.bot.api.model.WalkStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WalkerTest {

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
