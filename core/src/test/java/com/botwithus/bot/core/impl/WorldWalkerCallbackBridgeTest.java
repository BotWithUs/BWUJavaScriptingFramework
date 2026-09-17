package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.component.ComponentNode;
import com.botwithus.bot.api.component.ComponentQuery;
import com.botwithus.bot.api.component.Components;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.inventory.Backpack;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.snapshot.DynamicRegion;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.Inventory;
import com.botwithus.bot.api.snapshot.InventoryItem;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.snapshot.Location;
import com.botwithus.bot.api.snapshot.Skill;
import com.botwithus.bot.core.worldwalker.CapabilitySnapshot;
import com.botwithus.bot.core.worldwalker.ChainStepKind;
import com.botwithus.bot.core.worldwalker.WwEvent;
import com.botwithus.bot.core.worldwalker.WwEventKind;
import com.botwithus.bot.core.worldwalker.WorldWalkerException;
import com.botwithus.bot.core.worldwalker.WwGoal;
import com.botwithus.bot.core.worldwalker.WwTile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorldWalkerCallbackBridgeTest {

    // Suppresses the surge near-goal guard in tests that don't care: a null goal
    // is the documented "no goal info" sentinel and skips the overshoot check.
    private static final WwGoal NO_GOAL = null;

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
        bridge = new WorldWalkerCallbackBridge(api, () -> snapshot, cancel, events::add, NO_GOAL);
    }

    private LocalPlayer player(int x, int y, int plane) {
        return new LocalPlayer(0, 0, x, y, plane, 0, -1, -1, 0, -1, 0, false, -1,
                LocalPlayer.HEALTH_UNKNOWN, LocalPlayer.HEALTH_UNKNOWN, List.<Skill>of());
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
        bridge = new WorldWalkerCallbackBridge(api, () -> null, cancel, events::add, NO_GOAL);

        WwTile pos = bridge.readPosition();

        assertEquals(0, pos.x());
        assertEquals(0, pos.y());
        assertEquals(0, pos.plane());
    }

    // readInstance decides, once per plan, whether the pathfinder resolves
    // collision through the instance grid or against the static map. Every
    // branch below is a case where answering "static" would produce a plausible
    // wrong route rather than a visible failure, so each one is pinned.

    @Test
    void readInstanceReturnsNullForStaticScene() {
        when(snapshot.dynamicRegion()).thenReturn(DynamicRegion.STATIC);

        assertNull(bridge.readInstance());
    }

    @Test
    void readInstanceReturnsNullWhenSnapshotAbsent() {
        bridge = new WorldWalkerCallbackBridge(api, () -> null, cancel, events::add, NO_GOAL);

        assertNull(bridge.readInstance());
    }

    /** An unstubbed snapshot answers null; that must not NPE the walk. */
    @Test
    void readInstanceReturnsNullWhenRegionAbsent() {
        when(snapshot.dynamicRegion()).thenReturn(null);

        assertNull(bridge.readInstance());
    }

    /**
     * A truncated grid means the producer dropped it for exceeding the wire cap,
     * so the scene is an instance we cannot describe at all. Answering "static"
     * would plan against the overworld collision sharing these coordinates.
     */
    @Test
    void readInstanceThrowsWhenGridTruncated() {
        DynamicRegion truncated = mock(DynamicRegion.class);
        when(truncated.isInstance()).thenReturn(true);
        when(truncated.isTruncated()).thenReturn(true);
        when(snapshot.dynamicRegion()).thenReturn(truncated);

        assertThrows(WorldWalkerException.class, () -> bridge.readInstance());
    }

    @Test
    void readInstanceReturnsStableCopyOfLiveGrid() {
        DynamicRegion live = mock(DynamicRegion.class);
        when(live.isInstance()).thenReturn(true);
        when(live.isTruncated()).thenReturn(false);
        when(live.originMapX()).thenReturn(40);
        when(live.originMapY()).thenReturn(50);
        when(live.gridW()).thenReturn(8);
        when(live.gridH()).thenReturn(8);
        when(live.chunkCount()).thenReturn(1);
        when(live.chunkAt(0)).thenReturn(0x1234);
        when(snapshot.dynamicRegion()).thenReturn(live);

        DynamicRegion result = bridge.readInstance();

        assertNotNull(result);
        // A detached copy, not the live flyweight — the whole point, since the
        // native side reads the descriptors after this returns.
        assertNotSame(live, result);
        assertEquals(40, result.originMapX());
        assertEquals(50, result.originMapY());
        assertEquals(8, result.gridW());
        assertEquals(1, result.chunkCount());
        assertEquals(0x1234, result.chunkAt(0));
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
    void isInterfaceOpenDelegatesToSnapshot() {
        // Bridge reads the canonical "is this interface mounted" signal from
        // the SHM-backed snapshot (no RPC); 1465 (minimap HUD) is always open
        // while in-game, so it lights up as true.
        when(snapshot.isInterfaceOpen(1465)).thenReturn(true);
        assertTrue(bridge.isInterfaceOpen(1465));
    }

    @Test
    void isInterfaceOpenReturnsFalseWhenSnapshotSaysClosed() {
        // Pre-click probe of a dialog interface — not yet mounted in the
        // engine's open-subs hashmap.
        when(snapshot.isInterfaceOpen(1092)).thenReturn(false);
        assertFalse(bridge.isInterfaceOpen(1092));
    }

    @Test
    void isInterfaceOpenReturnsFalseWhenSnapshotMissing() {
        // No snapshot acquired yet (pre-login frames). Conservative: treat
        // as closed so the executor waits rather than fires blind clicks.
        bridge = new WorldWalkerCallbackBridge(api, () -> null, cancel, events::add, NO_GOAL);
        assertFalse(bridge.isInterfaceOpen(1465));
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

    // ============================== Surge ==============================

    /** Wire up the components fluent API to return one mock node when scanning
     *  the given iface for the surge sprite. Other ifaces resolve to an empty
     *  query that returns null. */
    private void stubSurgeOnIface(int iface, int spriteComp) {
        Components componentsFacade = mock(Components.class);
        ComponentQuery hit  = mock(ComponentQuery.class);
        ComponentQuery miss = mock(ComponentQuery.class);
        ComponentNode  node = mock(ComponentNode.class);
        when(api.components()).thenReturn(componentsFacade);
        when(componentsFacade.in(anyInt())).thenReturn(miss);
        when(componentsFacade.in(iface)).thenReturn(hit);
        when(hit.withSpriteId(anyInt())).thenReturn(hit);
        when(miss.withSpriteId(anyInt())).thenReturn(miss);
        when(hit.first()).thenReturn(node);
        when(miss.first()).thenReturn(null);
        when(node.componentId()).thenReturn(spriteComp);
        when(node.interfaceId()).thenReturn(iface);
    }

    private LocalPlayer playerWithMagic(int x, int y, int plane, int magicLevel) {
        Skill magic = new Skill(6, 0, magicLevel, magicLevel);
        return new LocalPlayer(0, 0, x, y, plane, 0, -1, -1, 0, -1, 0, false, -1,
                LocalPlayer.HEALTH_UNKNOWN, LocalPlayer.HEALTH_UNKNOWN, List.of(magic));
    }

    @Test
    void walkToFiresSurgeOnLongStraightChunkWhenMagicLevelIsHigh() {
        // Magic 99, player at (3000,3000), target 10 tiles due east, no goal
        // guard active (NO_GOAL). Surge slot is on iface 1670, sprite at comp 165
        // → click target at 166.
        when(snapshot.self()).thenReturn(playerWithMagic(3000, 3000, 0, 99));
        stubSurgeOnIface(1670, 165);

        bridge.walkTo(new WwTile(3010, 3000, 0));

        ArgumentCaptor<GameAction> captor = ArgumentCaptor.forClass(GameAction.class);
        verify(api, times(2)).queueAction(captor.capture());
        List<GameAction> actions = captor.getAllValues();
        // Walk goes first so the engine orients the avatar before surge drains
        // off the queue.
        assertEquals(ActionTypes.WALK, actions.get(0).actionId());
        GameAction surge = actions.get(1);
        assertEquals(ActionTypes.COMPONENT, surge.actionId());
        assertEquals(1, surge.param1(), "option index");
        assertEquals(-1, surge.param2(), "no sub-slot");
        assertEquals((1670 << 16) | 166, surge.param3(), "(iface<<16)|click_comp");
    }

    @Test
    void walkToSkipsSurgeBelowMinTiles() {
        // Same direction but only 6 tiles away (< SURGE_MIN_TILES = 8). Only the
        // plain walk should queue.
        when(snapshot.self()).thenReturn(playerWithMagic(3000, 3000, 0, 99));
        stubSurgeOnIface(1670, 165);

        bridge.walkTo(new WwTile(3006, 3000, 0));

        verify(api, times(1)).queueAction(any(GameAction.class));
    }

    @Test
    void walkToSkipsSurgeOnBentPath() {
        // L-shaped offset (dx=10, dy=4) — not cardinal, not pure-diagonal, so
        // surge would waste the cooldown moving off the path.
        when(snapshot.self()).thenReturn(playerWithMagic(3000, 3000, 0, 99));
        stubSurgeOnIface(1670, 165);

        bridge.walkTo(new WwTile(3010, 3004, 0));

        verify(api, times(1)).queueAction(any(GameAction.class));
    }

    @Test
    void walkToSkipsSurgeWhenMagicLevelBelowGate() {
        // Magic 20 (< 24) — Surge is locked. The slot scan never runs.
        when(snapshot.self()).thenReturn(playerWithMagic(3000, 3000, 0, 20));

        bridge.walkTo(new WwTile(3010, 3000, 0));

        verify(api, times(1)).queueAction(any(GameAction.class));
        verify(api, never()).components();
    }

    @Test
    void walkToSkipsSurgeWhenNearGoal() {
        // Goal-aware overshoot guard: with the goal 8 tiles east of the player
        // (< SURGE_GOAL_GUARD = 12), surge would land us past the goal even
        // though the walk chunk itself is a 10-tile straight run.
        WwGoal nearGoal = new WwGoal(3008, 3000, 0, 1);
        bridge = new WorldWalkerCallbackBridge(api, () -> snapshot, cancel, events::add, nearGoal);
        when(snapshot.self()).thenReturn(playerWithMagic(3000, 3000, 0, 99));

        bridge.walkTo(new WwTile(3010, 3000, 0));

        verify(api, times(1)).queueAction(any(GameAction.class));
        verify(api, never()).components();
    }

    @Test
    void walkToSurgesOnPureDiagonal() {
        // Pure diagonal (dx=dy=10) is the other valid straight path.
        when(snapshot.self()).thenReturn(playerWithMagic(3000, 3000, 0, 99));
        stubSurgeOnIface(1670, 165);

        bridge.walkTo(new WwTile(3010, 3010, 0));

        ArgumentCaptor<GameAction> captor = ArgumentCaptor.forClass(GameAction.class);
        verify(api, times(2)).queueAction(captor.capture());
        assertEquals(ActionTypes.COMPONENT, captor.getAllValues().get(1).actionId());
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
    void runChainStepClickQueuesActionVerbatim() {
        // A CLICK step is already a ready-to-queue action (a=actionId, b..d=
        // param1..3); the bridge forwards it verbatim. Here: a Lumbridge lodestone
        // select click — COMPONENT, option=1, sub=-1, hash=(1092<<16)|17.
        int hash = (1092 << 16) | 17;
        bridge.runChainStep(ChainStepKind.CLICK.wire(),
                ActionTypes.COMPONENT, 1, -1, hash, 0, 0, 0, 0, 0);

        ArgumentCaptor<GameAction> captor = ArgumentCaptor.forClass(GameAction.class);
        verify(api).queueAction(captor.capture());
        GameAction action = captor.getValue();
        assertEquals(ActionTypes.COMPONENT, action.actionId());
        assertEquals(1, action.param1(), "option index in param1");
        assertEquals(-1, action.param2(), "sub-component in param2");
        assertEquals(hash, action.param3(), "packed component hash in param3");
    }

    @Test
    void runChainStepClickItemBackpackSpecialUsesComponentSpecial() {
        // The executor pre-resolves the variant; the bridge receives a single
        // variant (a=iface, b=comp, c=option, d=sub, e=special). A backpack
        // special click maps to COMPONENT_SPECIAL with hash=(iface<<16)|comp.
        bridge.runChainStep(ChainStepKind.CLICK_ITEM.wire(),
                1473, 5, 7, 1, /*special=*/1, 0, 0, 0, 0);

        ArgumentCaptor<GameAction> captor = ArgumentCaptor.forClass(GameAction.class);
        verify(api).queueAction(captor.capture());
        GameAction action = captor.getValue();
        assertEquals(ActionTypes.COMPONENT_SPECIAL, action.actionId());
        assertEquals(7, action.param1(), "option index in param1");
        assertEquals(1, action.param2(), "sub-component in param2");
        assertEquals((1473 << 16) | 5, action.param3(), "packed component hash in param3");
    }

    @Test
    void runChainStepClickItemWornUsesComponent() {
        bridge.runChainStep(ChainStepKind.CLICK_ITEM.wire(),
                1464, 15, 3, 1, /*special=*/0, 0, 0, 0, 0);

        ArgumentCaptor<GameAction> captor = ArgumentCaptor.forClass(GameAction.class);
        verify(api).queueAction(captor.capture());
        assertEquals(ActionTypes.COMPONENT, captor.getValue().actionId());
        assertEquals((1464 << 16) | 15, captor.getValue().param3());
    }

    @Test
    void runChainStepClickItemResolvesLiveBackpackSlot() {
        // The cape (item 34295) sits in backpack slot 12, not the baked slot 1.
        // When the executor passes the carried item id (f), the bridge must use
        // the live slot for param2, overriding the baked sub.
        GameSnapshot.Inventories invs = mock(GameSnapshot.Inventories.class);
        when(snapshot.inventories()).thenReturn(invs);
        Inventory backpack = new Inventory(Backpack.INVENTORY_ID, 28,
                List.of(new InventoryItem(12, 34295, 1)));
        when(invs.byInvId(Backpack.INVENTORY_ID)).thenReturn(Optional.of(backpack));

        // Executor-resolved backpack variant: iface=1473, comp=5, option=7,
        // baked sub=1, special=1, carried item id=34295 in slot f.
        bridge.runChainStep(ChainStepKind.CLICK_ITEM.wire(),
                1473, 5, 7, 1, 1, 34295, 0, 0, 0);

        ArgumentCaptor<GameAction> captor = ArgumentCaptor.forClass(GameAction.class);
        verify(api).queueAction(captor.capture());
        GameAction action = captor.getValue();
        assertEquals(ActionTypes.COMPONENT_SPECIAL, action.actionId());
        assertEquals(7, action.param1(), "context-menu option in param1");
        assertEquals(12, action.param2(), "LIVE backpack slot 12 overrides baked slot 1");
        assertEquals((1473 << 16) | 5, action.param3());
    }

    @Test
    void runChainStepClickItemFallsBackToBakedSlotWhenItemAbsent() {
        // Item not in the backpack -> keep the baked sub as a fallback.
        GameSnapshot.Inventories invs = mock(GameSnapshot.Inventories.class);
        when(snapshot.inventories()).thenReturn(invs);
        when(invs.byInvId(Backpack.INVENTORY_ID)).thenReturn(Optional.empty());

        bridge.runChainStep(ChainStepKind.CLICK_ITEM.wire(),
                1473, 5, 7, 1, 1, 34295, 0, 0, 0);

        ArgumentCaptor<GameAction> captor = ArgumentCaptor.forClass(GameAction.class);
        verify(api).queueAction(captor.capture());
        assertEquals(1, captor.getValue().param2(), "baked slot 1 used when item not found");
    }

    @Test
    void runChainStepDialogueSelectClicksOptionComponent() {
        // index=1 on a single page -> option component DIALOGUE_OPTION_COMPS[1]=20
        // on interface 720, dispatched as a DIALOGUE action.
        bridge.runChainStep(ChainStepKind.DIALOGUE_SELECT.wire(),
                720, 1, 9, 44, 3, 0, 0, 0, 0);

        ArgumentCaptor<GameAction> captor = ArgumentCaptor.forClass(GameAction.class);
        verify(api).queueAction(captor.capture());
        GameAction action = captor.getValue();
        assertEquals(ActionTypes.DIALOGUE, action.actionId());
        assertEquals((720 << 16) | 20, action.param3(), "option component 20 for index 1");
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
