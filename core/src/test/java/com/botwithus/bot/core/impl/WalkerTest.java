package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.*;
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

    @Test
    void getNavStatsDelegatesToApi() {
        var expected = new NavStats(500, 42, 15, 30, 8, 25, 85, 70, 15);
        when(api.navGetStats()).thenReturn(expected);
        assertSame(expected, walker.getNavStats());
    }

    // ============================== Add + Cleanup Tracking ==============================

    @Test
    void addTransportDelegatesAndTracksCleanup() {
        var transport = new NavTransport(100, 3200, 3200, 3210, 3210);
        walker.addTransport(transport);

        verify(api).navAddTransport(transport);

        walker.cleanup();
        verify(api).navRemoveTransport(100, 3200, 3200, 0);
    }

    @Test
    void addTransportWithPlaneDelegatesAndTracksCleanup() {
        var transport = new NavTransport(100, 3200, 3200, 1, 10, 0, 0, 3210, 3210, 2);
        walker.addTransport(transport);

        verify(api).navAddTransport(transport);

        walker.cleanup();
        verify(api).navRemoveTransport(100, 3200, 3200, 1);
    }

    @Test
    void addDoorDelegatesAndTracksCleanup() {
        var door = new NavDoor(200, 3100, 3100);
        walker.addDoor(door);

        verify(api).navAddDoor(door);

        walker.cleanup();
        verify(api).navRemoveDoor(200, 3100, 3100, 0);
    }

    @Test
    void addShortcutDelegatesAndTracksCleanup() {
        var shortcut = new NavShortcut(300, 3050, 3050, 60);
        walker.addShortcut(shortcut);

        verify(api).navAddShortcut(shortcut);

        walker.cleanup();
        verify(api).navRemoveShortcut(300, 3050, 3050, 0);
    }

    @Test
    void addPlaneTransitionDelegatesAndTracksCleanup() {
        var transition = new NavPlaneTransition(400, 3000, 3000, 1);
        walker.addPlaneTransition(transition);

        verify(api).navAddPlaneTransition(transition);

        walker.cleanup();
        verify(api).navRemovePlaneTransition(400, 3000, 3000, 0);
    }

    @Test
    void addClimboverDelegatesAndTracksCleanup() {
        var climbover = new NavClimbover(500, 2900, 2900);
        walker.addClimbover(climbover);

        verify(api).navAddClimbover(climbover);

        walker.cleanup();
        verify(api).navRemoveClimbover(500, 2900, 2900, 0);
    }

    // ============================== Cleanup Behavior ==============================

    @Test
    void cleanupReversesInOrder() {
        var transport = new NavTransport(1, 10, 20, 30, 40);
        var door = new NavDoor(2, 50, 60);
        var shortcut = new NavShortcut(3, 70, 80, 50);

        walker.addTransport(transport);
        walker.addDoor(door);
        walker.addShortcut(shortcut);

        walker.cleanup();

        var order = inOrder(api);
        order.verify(api).walkCancel();
        // Reverse order: shortcut, door, transport
        order.verify(api).navRemoveShortcut(3, 70, 80, 0);
        order.verify(api).navRemoveDoor(2, 50, 60, 0);
        order.verify(api).navRemoveTransport(1, 10, 20, 0);
    }

    @Test
    void cleanupCancelsActiveWalk() {
        walker.cleanup();
        verify(api).walkCancel();
    }

    @Test
    void cleanupClearsScriptTeleportsWhenRegistered() {
        when(api.navRegisterTeleports(anyString(), anyString())).thenReturn(5);
        walker.registerTeleports("{}", "item_teleports");

        walker.cleanup();

        verify(api).navClearScriptTeleports();
    }

    @Test
    void cleanupSkipsTeleportClearWhenNoneRegistered() {
        walker.cleanup();
        verify(api, never()).navClearScriptTeleports();
    }

    @Test
    void cleanupSkipsTeleportClearWhenZeroAdded() {
        when(api.navRegisterTeleports(anyString(), anyString())).thenReturn(0);
        walker.registerTeleports("{}", "item_teleports");

        walker.cleanup();

        verify(api, never()).navClearScriptTeleports();
    }

    @Test
    void cleanupIsIdempotent() {
        var door = new NavDoor(1, 10, 20);
        walker.addDoor(door);

        walker.cleanup();
        walker.cleanup(); // second call should not re-remove

        verify(api, times(1)).navRemoveDoor(1, 10, 20, 0);
        verify(api, times(2)).walkCancel(); // cancel is called each time
    }

    @Test
    void cleanupContinuesOnFailure() {
        var transport = new NavTransport(1, 10, 20, 30, 40);
        var door = new NavDoor(2, 50, 60);
        walker.addTransport(transport);
        walker.addDoor(door);

        // Make the door removal throw
        doThrow(new RuntimeException("simulated error"))
                .when(api).navRemoveDoor(2, 50, 60, 0);

        walker.cleanup();

        // Transport removal should still happen despite door removal failure
        verify(api).navRemoveTransport(1, 10, 20, 0);
    }

    // ============================== Remove (no tracking) ==============================

    @Test
    void removeTransportDelegatesWithoutTracking() {
        walker.removeTransport(100, 3200, 3200, 0);
        verify(api).navRemoveTransport(100, 3200, 3200, 0);

        walker.cleanup();
        // Should not call remove again
        verify(api, times(1)).navRemoveTransport(100, 3200, 3200, 0);
    }

    @Test
    void removeDoorDelegatesWithoutTracking() {
        walker.removeDoor(200, 3100, 3100, 0);
        verify(api).navRemoveDoor(200, 3100, 3100, 0);
    }

    @Test
    void removeShortcutDelegatesWithoutTracking() {
        walker.removeShortcut(300, 3050, 3050, 0);
        verify(api).navRemoveShortcut(300, 3050, 3050, 0);
    }

    @Test
    void removePlaneTransitionDelegatesWithoutTracking() {
        walker.removePlaneTransition(400, 3000, 3000, 0);
        verify(api).navRemovePlaneTransition(400, 3000, 3000, 0);
    }

    @Test
    void removeClimboverDelegatesWithoutTracking() {
        walker.removeClimbover(500, 2900, 2900, 0);
        verify(api).navRemoveClimbover(500, 2900, 2900, 0);
    }

    // ============================== Listing ==============================

    @Test
    void listTransportsDelegatesToApi() {
        var expected = List.of(new NavTransport(1, 10, 20, 30, 40));
        when(api.navListTransports()).thenReturn(expected);
        assertEquals(expected, walker.listTransports());
    }

    @Test
    void listDoorsDelegatesToApi() {
        var expected = List.of(new NavDoor(1, 10, 20));
        when(api.navListDoors()).thenReturn(expected);
        assertEquals(expected, walker.listDoors());
    }

    @Test
    void listTeleportsDelegatesToApi() {
        var expected = List.of(new NavTeleport(0, "Lumbridge", true, 3233, 3222, 0, 80.0, 25.0, 4, 0, true));
        when(api.navListTeleports(false)).thenReturn(expected);
        assertEquals(expected, walker.listTeleports(false));
    }

    @Test
    void listTeleportsScriptOnlyDelegatesToApi() {
        when(api.navListTeleports(true)).thenReturn(List.of());
        assertEquals(List.of(), walker.listTeleports(true));
        verify(api).navListTeleports(true);
    }

    // ============================== Teleports ==============================

    @Test
    void registerTeleportsDelegatesToApi() {
        when(api.navRegisterTeleports("{}", "gibson")).thenReturn(3);
        assertEquals(3, walker.registerTeleports("{}", "gibson"));
    }

    @Test
    void registerTeleportsDefaultFormat() {
        when(api.navRegisterTeleports("{}", "item_teleports")).thenReturn(2);
        assertEquals(2, walker.registerTeleports("{}"));
    }

    @Test
    void clearScriptTeleportsDelegatesToApi() {
        when(api.navClearScriptTeleports()).thenReturn(5);
        assertEquals(5, walker.clearScriptTeleports());
    }

    @Test
    void clearScriptTeleportsResetsTrackingFlag() {
        when(api.navRegisterTeleports(anyString(), anyString())).thenReturn(3);
        walker.registerTeleports("{}");

        when(api.navClearScriptTeleports()).thenReturn(3);
        walker.clearScriptTeleports();

        // Cleanup should NOT call clearScriptTeleports again since we already cleared
        walker.cleanup();
        verify(api, times(1)).navClearScriptTeleports();
    }

    // ============================== Batch Operations ==============================

    @Test
    void loadLinksJsonDelegatesToApi() {
        var links = List.of(new NavTransport(1, 10, 20, 30, 40));
        when(api.navLoadJson(links)).thenReturn(1);
        assertEquals(1, walker.loadLinksJson(links));
    }

    @Test
    void saveLinksDelegatesToApi() {
        walker.saveLinks("test.bin");
        verify(api).navSaveLinks("test.bin");
    }

    @Test
    void saveLinksDelegatesNullPath() {
        walker.saveLinks(null);
        verify(api).navSaveLinks(null);
    }

    @Test
    void loadLinksDelegatesToApi() {
        when(api.navLoadLinks("test.bin")).thenReturn(12);
        assertEquals(12, walker.loadLinks("test.bin"));
    }

    // ============================== Model Record Defaults ==============================

    @Test
    void navTransportConvenienceConstructor() {
        var t = new NavTransport(100, 3200, 3200, 3210, 3210);
        assertEquals(0, t.plane());
        assertEquals(10, t.shape());
        assertEquals(0, t.rotation());
        assertEquals(0, t.optionIndex());
        assertEquals(0, t.destPlane());
    }

    @Test
    void navDoorConvenienceConstructor() {
        var d = new NavDoor(200, 3100, 3100);
        assertEquals(0, d.plane());
        assertEquals(0, d.shape());
        assertEquals(0, d.rotation());
    }

    @Test
    void navShortcutConvenienceConstructor() {
        var s = new NavShortcut(300, 3050, 3050, 60);
        assertEquals(0, s.plane());
        assertEquals(0, s.shape());
        assertEquals(0, s.rotation());
        assertEquals(60, s.agilityLevel());
    }

    @Test
    void navPlaneTransitionConvenienceConstructor() {
        var p = new NavPlaneTransition(400, 3000, 3000, 2);
        assertEquals(0, p.plane());
        assertEquals(10, p.shape());
        assertEquals(0, p.rotation());
        assertEquals(1, p.sizeX());
        assertEquals(1, p.sizeY());
        assertEquals(-1, p.destX());
        assertEquals(-1, p.destY());
        assertEquals(2, p.destPlane());
    }

    @Test
    void navClimboverConvenienceConstructor() {
        var c = new NavClimbover(500, 2900, 2900);
        assertEquals(0, c.plane());
        assertEquals(0, c.shape());
        assertEquals(0, c.rotation());
    }
}
