package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.ComponentTreeNode;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.snapshot.Location;
import com.botwithus.bot.api.snapshot.Skill;
import com.botwithus.bot.core.worldwalker.CapabilitySnapshot;
import com.botwithus.bot.core.worldwalker.WwEvent;
import com.botwithus.bot.core.worldwalker.WwEventKind;
import com.botwithus.bot.core.worldwalker.WwTile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorldWalkerCallbackBridgeTest {

    private GameAPI api;
    private GameSnapshot snapshot;
    private GameSnapshot.Locations locationsTable;
    private AtomicBoolean cancel;
    private List<WwEvent> events;
    private WorldWalkerCallbackBridge bridge;

    @BeforeEach
    void setUp() {
        api = mock(GameAPI.class);
        snapshot = mock(GameSnapshot.class);
        locationsTable = mock(GameSnapshot.Locations.class);
        when(snapshot.locations()).thenReturn(locationsTable);
        when(locationsTable.stream()).thenReturn(Stream.empty());
        cancel = new AtomicBoolean(false);
        events = new ArrayList<>();
        bridge = new WorldWalkerCallbackBridge(api, () -> snapshot, cancel, events::add);
    }

    private LocalPlayer player(int x, int y, int plane) {
        return new LocalPlayer(0, 0, x, y, plane, 0, -1, -1, 0, -1, 0, false, List.<Skill>of());
    }

    // ============================== Reads ==============================

    @Test
    void readPositionReturnsSnapshotTile() {
        when(snapshot.self()).thenReturn(player(3221, 3219, 0));

        WwTile pos = bridge.readPosition();

        assertEquals(3221, pos.x());
        assertEquals(3219, pos.y());
        assertEquals(0, pos.plane());
    }

    @Test
    void readPositionFallsBackWhenSnapshotNull() {
        bridge = new WorldWalkerCallbackBridge(api, () -> null, cancel, events::add);

        WwTile pos = bridge.readPosition();

        assertEquals(0, pos.x());
        assertEquals(0, pos.y());
        assertEquals(0, pos.plane());
    }

    @Test
    void readPositionFallsBackWhenPlayerNull() {
        when(snapshot.self()).thenReturn(null);

        WwTile pos = bridge.readPosition();

        assertEquals(0, pos.x());
        assertEquals(0, pos.y());
        assertEquals(0, pos.plane());
    }

    @Test
    void readCapabilityIsEmpty() {
        CapabilitySnapshot caps = bridge.readCapability();
        assertTrue(caps.isEmpty());
    }

    @Test
    void readVarbitDelegatesToApi() {
        when(api.getVarbit(42)).thenReturn(7);
        assertEquals(7, bridge.readVarbit(42));
    }

    @Test
    void readVarbitReturnsZeroOnApiException() {
        when(api.getVarbit(99)).thenThrow(new RuntimeException("rpc down"));
        assertEquals(0, bridge.readVarbit(99));
    }

    @Test
    void isInterfaceOpenReturnsTrueWhenTreeNonEmpty() {
        when(api.getInterfaceTree(1234, 0))
                .thenReturn(List.of(mock(ComponentTreeNode.class)));
        assertTrue(bridge.isInterfaceOpen(1234));
    }

    @Test
    void isInterfaceOpenReturnsFalseWhenTreeEmpty() {
        when(api.getInterfaceTree(1234, 0)).thenReturn(List.of());
        assertFalse(bridge.isInterfaceOpen(1234));
    }

    @Test
    void isInterfaceOpenReturnsFalseOnApiException() {
        when(api.getInterfaceTree(1234, 0)).thenThrow(new RuntimeException("rpc down"));
        assertFalse(bridge.isInterfaceOpen(1234));
    }

    // ============================== Actions ==============================

    @Test
    void walkToQueuesMinimapAction() {
        bridge.walkTo(new WwTile(3221, 3219, 0));

        ArgumentCaptor<GameAction> captor = ArgumentCaptor.forClass(GameAction.class);
        verify(api).queueAction(captor.capture());
        GameAction action = captor.getValue();
        assertEquals(ActionTypes.WALK, action.actionId());
        assertEquals(1, action.param1());
        assertEquals(3221, action.param2());
        assertEquals(3219, action.param3());
    }

    @Test
    void interactResolvesLocAndQueuesObjectAction() {
        // The engine's object DoAction is (locTypeId, worldX, worldY) — the same
        // shape a manual click emits — not a scene handle in param1.
        Location matching = new Location(
                /* typeId= */ 1234, /* interactId= */ 0x1A2B3C, /* animationId= */ -1,
                /* tileX= */ 3221, /* tileY= */ 3219, /* plane= */ 0,
                /* shape= */ 10, /* rotation= */ 0, /* flags= */ 0);
        when(locationsTable.stream()).thenReturn(Stream.of(matching));

        int issued = bridge.interact(1234, new WwTile(3221, 3219, 0), 0);

        assertEquals(1, issued, "queuing an action must report issued=1");
        ArgumentCaptor<GameAction> captor = ArgumentCaptor.forClass(GameAction.class);
        verify(api).queueAction(captor.capture());
        GameAction action = captor.getValue();
        assertEquals(ActionTypes.OBJECT1, action.actionId());
        assertEquals(1234, action.param1());
        assertEquals(3221, action.param2());
        assertEquals(3219, action.param3());
    }

    @Test
    void interactResolvesCombinedSectionDoorByTile() {
        // Doors and stairs are published as COMBINED_LOCATION_SECTIONs:
        // interactId == -1 and the section flag set. Resolution must still
        // succeed by type + tile and fire the (typeId, worldX, worldY) action.
        int sectionFlag = 1 << 1; // Layout.LOC_FLAG_COMBINED_SECTION
        Location door = new Location(
                45476, -1, -1, 3228, 3240, 0, /* shape= */ 0, /* rotation= */ 2, sectionFlag);
        when(locationsTable.stream()).thenReturn(Stream.of(door));

        bridge.interact(45476, new WwTile(3228, 3240, 0), 0);

        ArgumentCaptor<GameAction> captor = ArgumentCaptor.forClass(GameAction.class);
        verify(api).queueAction(captor.capture());
        GameAction action = captor.getValue();
        assertEquals(ActionTypes.OBJECT1, action.actionId());
        assertEquals(45476, action.param1());
        assertEquals(3228, action.param2());
        assertEquals(3240, action.param3());
    }

    @Test
    void interactOptionTwoUsesSecondActionId() {
        Location matching = new Location(
                1234, 99, -1, 100, 200, 0, 10, 0, 0);
        when(locationsTable.stream()).thenReturn(Stream.of(matching));

        bridge.interact(1234, new WwTile(100, 200, 0), 1);

        ArgumentCaptor<GameAction> captor = ArgumentCaptor.forClass(GameAction.class);
        verify(api).queueAction(captor.capture());
        assertEquals(ActionTypes.OBJECT2, captor.getValue().actionId());
    }

    @Test
    void interactResolvesLocOnAdjacentTile() {
        // A door loc sits one tile off the transition origin (reverse-direction
        // hop: we stand on the far side). It must resolve within Chebyshev
        // radius 1 and the action must target the loc's OWN tile, not the origin.
        Location adjacent = new Location(
                1234, 0xBEEF, -1, 3222, 3219, 0, 0, 0, 0);
        when(locationsTable.stream()).thenReturn(Stream.of(adjacent));

        bridge.interact(1234, new WwTile(3221, 3219, 0), 0);

        ArgumentCaptor<GameAction> captor = ArgumentCaptor.forClass(GameAction.class);
        verify(api).queueAction(captor.capture());
        GameAction action = captor.getValue();
        assertEquals(1234, action.param1());
        assertEquals(3222, action.param2());
        assertEquals(3219, action.param3());
    }

    @Test
    void interactPrefersExactTileOverAdjacent() {
        Location adjacent = new Location(1234, 0xAAAA, -1, 3222, 3219, 0, 0, 0, 0);
        Location exact = new Location(1234, 0xBBBB, -1, 3221, 3219, 0, 0, 0, 0);
        when(locationsTable.stream()).thenReturn(Stream.of(adjacent, exact));

        bridge.interact(1234, new WwTile(3221, 3219, 0), 0);

        ArgumentCaptor<GameAction> captor = ArgumentCaptor.forClass(GameAction.class);
        verify(api).queueAction(captor.capture());
        GameAction action = captor.getValue();
        // The exact-tile loc wins, so the action targets (3221,3219).
        assertEquals(3221, action.param2());
        assertEquals(3219, action.param3());
    }

    @Test
    void interactSkipsWhenNoMatchingLoc() {
        // The baked (closed) door / stairs loc is absent from the live scene:
        // an already-open door is a different loc id, so it isn't found. The
        // bridge must NOT queue an action — it treats the obstacle as gone and
        // lets the executor advance to the next step (which walks through the
        // open doorway).
        when(locationsTable.stream()).thenReturn(Stream.empty());

        int issued = bridge.interact(1234, new WwTile(3221, 3219, 0), 0);

        assertEquals(0, issued, "a skipped no-op (already-open door) must report issued=0");
        verifyNoInteractions(api);
    }

    @Test
    void interactSkipsWhenLocBeyondRadius() {
        // Two tiles away (Chebyshev 2) is beyond the radius-1 door approach
        // tolerance, so it must not resolve.
        Location other = new Location(1234, 99, -1, 3223, 3219, 0, 10, 0, 0);
        when(locationsTable.stream()).thenReturn(Stream.of(other));

        bridge.interact(1234, new WwTile(3221, 3219, 0), 0);

        verifyNoInteractions(api);
    }

    @Test
    void interactSkipsOutOfRangeOptionIndex() {
        int issued = bridge.interact(1234, new WwTile(0, 0, 0), 99);
        assertEquals(0, issued, "out-of-range option must report issued=0");
        verifyNoInteractions(api);
    }

    @Test
    void runChainStepQueuesActionVerbatim() {
        // The chain step is already a ready-to-queue action (actionId, p1, p2,
        // p3); the bridge forwards it verbatim with no packing. Here: a Lumbridge
        // lodestone select click — COMPONENT, option=1, sub=-1, hash=(1092<<16)|17.
        int hash = (1092 << 16) | 17;
        bridge.runChainStep(ActionTypes.COMPONENT, 1, -1, hash);

        ArgumentCaptor<GameAction> captor = ArgumentCaptor.forClass(GameAction.class);
        verify(api).queueAction(captor.capture());
        GameAction action = captor.getValue();
        assertEquals(ActionTypes.COMPONENT, action.actionId());
        assertEquals(1, action.param1(), "option index in param1");
        assertEquals(-1, action.param2(), "sub-component in param2");
        assertEquals(hash, action.param3(), "packed component hash in param3");
    }

    @Test
    void sleepTicksSleepsApproxSixHundredMsPerTick() throws Exception {
        long start = System.nanoTime();
        bridge.sleepTicks(2);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMs >= 1100, "expected >= 1100ms, got " + elapsedMs);
        assertTrue(elapsedMs < 2000, "expected < 2000ms, got " + elapsedMs);
    }

    @Test
    void sleepTicksZeroReturnsImmediately() {
        long start = System.nanoTime();
        bridge.sleepTicks(0);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMs < 50, "expected immediate return, got " + elapsedMs + "ms");
    }

    // ============================== Control + progress ==============================

    @Test
    void shouldCancelMirrorsAtomic() {
        assertFalse(bridge.shouldCancel());
        cancel.set(true);
        assertTrue(bridge.shouldCancel());
    }

    @Test
    void onEventForwardsToSink() {
        WwEvent event = new WwEvent(WwEventKind.STEP_ADVANCED, 0, 3, -1);
        bridge.onEvent(event);
        assertEquals(1, events.size());
        assertSame(event, events.get(0));
    }
}
