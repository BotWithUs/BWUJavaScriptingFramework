package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.model.VarKind;
import com.botwithus.bot.api.model.VarpState;
import com.botwithus.bot.api.model.VarpRead;
import com.botwithus.bot.api.model.VarbitRead;
import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.component.ComponentNode;
import com.botwithus.bot.api.component.ComponentQuery;
import com.botwithus.bot.api.component.ComponentType;
import com.botwithus.bot.api.component.Components;
import com.botwithus.bot.api.dialog.Dialog;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.inventory.Backpack;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.ComponentTreeNode;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.LocationType;
import com.botwithus.bot.api.model.StructType;
import com.botwithus.bot.api.snapshot.DynamicRegion;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.Inventory;
import com.botwithus.bot.api.snapshot.InventoryItem;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.snapshot.Location;
import com.botwithus.bot.api.snapshot.Npc;
import com.botwithus.bot.api.snapshot.Skill;
import com.botwithus.bot.core.worldwalker.CapabilitySnapshot;
import com.botwithus.bot.core.worldwalker.ChainStepKind;
import com.botwithus.bot.core.worldwalker.DialogueAnswerText;
import com.botwithus.bot.core.worldwalker.WwEvent;
import com.botwithus.bot.core.worldwalker.WwEventKind;
import com.botwithus.bot.core.worldwalker.WorldWalkerException;
import com.botwithus.bot.core.worldwalker.WwGoal;
import com.botwithus.bot.core.worldwalker.WwTile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorldWalkerCallbackBridgeTest {

    // Suppresses the surge near-goal guard in tests that don't care: a null goal
    // is the documented "no goal info" sentinel and skips the overshoot check.
    private static final WwGoal NO_GOAL = null;

    // Magic's StatType.id, the skill the test players carry.
    private static final int MAGIC_SKILL_TYPE = 6;

    // Sabbot's cave entrance, a 4x4 loc whose dataset row sits off its anchor.
    private static final int SABBOT_CAVE = 34395;

    // RandomGenerator.nextDouble() is built from nextLong(): 0 maps to 0.0 (every
    // chance roll passes), all bits set to just under 1.0 (every roll fails).
    private static final RandomGenerator ALWAYS_FIRE = () -> 0L;
    private static final RandomGenerator NEVER_FIRE  = () -> -1L;
    // nextDouble() near 0.0 (fires) with nextInt() == 1, which picks the
    // 2-tick post-ability pause.
    private static final RandomGenerator TWO_TICK_PAUSE = () -> 1L << Integer.SIZE;

    private static final long ONE_TICK_MS = 600L;
    private static final int  SOME_ANIMATION = 8939;

    // Test clock start; any positive epoch-ms value works.
    private static final long START_MS = 1_000_000L;
    private static final long RESCAN_MS = 60_000L;

    // Bar icon sprites the cache structs name (param 2802).
    private static final int SURGE_SPRITE       = 14233;
    private static final int DIVE_SPRITE        = 23714;
    private static final int BLADED_DIVE_SPRITE = 30331;

    private GameAPI api;
    private AtomicLong clock;
    private List<Long> sleeps;
    private Components componentsFacade;
    private Map<Integer, ComponentQuery> barQueries;
    private GameSnapshot snapshot;
    private GameSnapshot.Locations locationsTable;
    private GameSnapshot.Npcs npcsTable;
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
        npcsTable = mock(GameSnapshot.Npcs.class);
        when(snapshot.npcs()).thenReturn(npcsTable);
        when(npcsTable.stream()).thenReturn(Stream.empty());
        cancel = new AtomicBoolean(false);
        events = new ArrayList<>();
        clock = new AtomicLong(START_MS);
        sleeps = new ArrayList<>();
        barQueries = new HashMap<>();
        bridge = bridgeWithRng(ALWAYS_FIRE);
    }

    private WorldWalkerCallbackBridge bridgeWithRng(RandomGenerator rng) {
        return new WorldWalkerCallbackBridge(api, () -> snapshot, cancel, events::add, NO_GOAL,
                WorldWalkerCallbackBridge.REQUIREMENT_VARPS, pacing(rng));
    }

    // Fake time and sleep: sleeps are recorded, not taken.
    private WorldWalkerCallbackBridge.Pacing pacing(RandomGenerator rng) {
        return new WorldWalkerCallbackBridge.Pacing(rng, clock::get, sleeps::add);
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
    void readCapabilityIsEmptyWithNoPlayer() {
        when(snapshot.self()).thenReturn(null);

        assertTrue(bridge.readCapability().isEmpty());
    }

    @Test
    void readCapabilityCarriesSkillLevels() {
        when(snapshot.self()).thenReturn(playerWithSkill(MAGIC_SKILL_TYPE, 73, 73));

        CapabilitySnapshot caps = bridge.readCapability();

        assertEquals(73, caps.skills().get(MAGIC_SKILL_TYPE));
    }

    // A boosted level admits a gate the player cannot still meet when the walk
    // arrives, so the snapshot carries the base level and the planner routes
    // through what the player actually has.
    @Test
    void readCapabilityCarriesBaseLevelNotBoosted() {
        when(snapshot.self()).thenReturn(playerWithSkill(MAGIC_SKILL_TYPE, 70, 99));

        CapabilitySnapshot caps = bridge.readCapability();

        assertEquals(70, caps.skills().get(MAGIC_SKILL_TYPE));
    }

    private static VarpRead setVarp(int id, int value) {
        return new VarpRead(id, VarpState.SET, value, value, VarKind.INT, true);
    }

    private static List<VarpRead> setVarps(List<Integer> ids, List<Integer> values) {
        List<VarpRead> reads = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            reads.add(setVarp(ids.get(i), values.get(i)));
        }
        return reads;
    }

    // Every varp the shipped WorldWalker dataset gates on, with the value its
    // gate needs: the spirit trees (2661), The Grand Tree (2740), the Mos
    // Le'Harmless (2326) and Port Tyras (2102) charters, and the Feldip Hills
    // glider (2671, One Small Favour). A gate on a varp missing here is denied.
    private static final Map<Integer, Integer> DATASET_VARP_GATES =
            Map.of(2661, 9, 2740, 160, 2326, 140, 2102, 15, 2671, 200);

    // The executor learns varps only from this snapshot, so a varp_at_least gate
    // (Tree Gnome Village's spirit trees) is denied unless the host puts it here.
    @Test
    void readCapabilityCarriesRequirementVarpsInOneBatchedRead() {
        when(snapshot.self()).thenReturn(playerWithSkill(MAGIC_SKILL_TYPE, 73, 73));
        when(api.readVarps(anyList())).thenAnswer(invocation -> {
            List<Integer> ids = invocation.getArgument(0);
            return setVarps(ids, ids.stream().map(id -> DATASET_VARP_GATES.getOrDefault(id, 0))
                    .toList());
        });

        CapabilitySnapshot caps = bridge.readCapability();

        assertEquals(DATASET_VARP_GATES, caps.varps());
        verify(api, times(1)).readVarps(anyList());
        verify(api, never()).readVarp(anyInt());
        verify(api, never()).getVarp(anyInt());
    }

    @Test
    void readCapabilityReadsTheVarpsItIsGiven() {
        bridge = new WorldWalkerCallbackBridge(api, () -> snapshot, cancel, events::add, NO_GOAL,
                List.of(7, 8));
        when(snapshot.self()).thenReturn(playerWithSkill(MAGIC_SKILL_TYPE, 73, 73));
        when(api.readVarps(List.of(7, 8))).thenReturn(setVarps(List.of(7, 8), List.of(1, 2)));

        CapabilitySnapshot caps = bridge.readCapability();

        assertEquals(Map.of(7, 1, 8, 2), caps.varps());
    }

    // The walker's ABI reads 0 as "not present"; a varp with no value is left out
    // rather than handed over as the host's -1 sentinel. A varp at its default
    // has a value, and is carried.
    @Test
    void readCapabilityLeavesOutVarpsWithNoValue() {
        bridge = new WorldWalkerCallbackBridge(api, () -> snapshot, cancel, events::add, NO_GOAL,
                List.of(7, 8, 9));
        when(snapshot.self()).thenReturn(playerWithSkill(MAGIC_SKILL_TYPE, 73, 73));
        when(api.readVarps(List.of(7, 8, 9))).thenReturn(List.of(
                new VarpRead(7, VarpState.UNAVAILABLE, VarpRead.NO_VALUE, VarpRead.NO_VALUE,
                        VarKind.UNKNOWN, true),
                new VarpRead(8, VarpState.DEFAULT_NOT_SET_CLIENTSIDE, 0, 0, VarKind.UNKNOWN, true),
                new VarpRead(9, VarpState.NO_SUCH_VARP, VarpRead.NO_VALUE, VarpRead.NO_VALUE,
                        VarKind.UNKNOWN, true)));

        CapabilitySnapshot caps = bridge.readCapability();

        assertEquals(Map.of(8, 0), caps.varps());
    }

    // A mis-sized reply cannot be paired with its ids, so it adds no varp at
    // all: every varp gate stays denied rather than admitting on another's value.
    @Test
    void readCapabilityAddsNoVarpsWhenBatchIsMisSized() {
        when(snapshot.self()).thenReturn(playerWithSkill(MAGIC_SKILL_TYPE, 73, 73));
        when(api.readVarps(anyList())).thenReturn(List.of(setVarp(2661, 9)));

        CapabilitySnapshot caps = bridge.readCapability();

        assertTrue(caps.varps().isEmpty());
        assertEquals(73, caps.skills().get(MAGIC_SKILL_TYPE));
    }

    @Test
    void readCapabilityKeepsSkillsWhenVarpReadFails() {
        when(snapshot.self()).thenReturn(playerWithSkill(MAGIC_SKILL_TYPE, 73, 73));
        when(api.readVarps(anyList())).thenThrow(new RuntimeException("rpc down"));

        CapabilitySnapshot caps = bridge.readCapability();

        assertTrue(caps.varps().isEmpty());
        assertEquals(73, caps.skills().get(MAGIC_SKILL_TYPE));
    }

    private LocalPlayer playerWithSkill(int typeId, int actualLevel, int boostedLevel) {
        Skill skill = new Skill(typeId, 0, actualLevel, boostedLevel);
        return new LocalPlayer(0, 0, 3000, 3000, 0, 0, -1, -1, 0, -1, 0, false, -1,
                LocalPlayer.HEALTH_UNKNOWN, LocalPlayer.HEALTH_UNKNOWN, List.of(skill));
    }

    @Test
    void readVarbitDelegatesToApi() {
        when(api.readVarbit(42)).thenReturn(new VarbitRead(42, VarpState.SET, 7, true));
        assertEquals(7, bridge.readVarbit(42));
    }

    /** The walker's ABI reads 0 as "not present"; the host's -1 must never reach it. */
    @Test
    void readVarbitWithNoValueReadsZeroForTheWalker() {
        when(api.readVarbit(43)).thenReturn(
                new VarbitRead(43, VarpState.UNAVAILABLE, VarpRead.NO_VALUE, true));
        assertEquals(0, bridge.readVarbit(43));
    }

    @Test
    void readVarbitReturnsZeroOnApiException() {
        when(api.readVarbit(99)).thenThrow(new RuntimeException("rpc down"));
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

    /** Surge's struct names its icon, and that icon sits on {@code iface} at {@code spriteComp}. */
    private void stubSurgeOnIface(int iface, int spriteComp) {
        stubStructSprite(WorldWalkerCallbackBridge.STRUCT_SURGE, SURGE_SPRITE);
        stubSpriteOnBar(iface, SURGE_SPRITE, spriteComp);
    }

    private void stubStructSprite(int structId, int spriteId) {
        when(api.getStructType(structId)).thenReturn(new StructType(structId,
                Map.of(WorldWalkerCallbackBridge.STRUCT_PARAM_ICON_SPRITE, spriteId)));
    }

    /** The components facade, created on first use; every bar starts out empty. */
    private Components components() {
        if (componentsFacade == null) {
            componentsFacade = mock(Components.class);
            ComponentQuery empty = emptyQuery();
            when(api.components()).thenReturn(componentsFacade);
            when(componentsFacade.in(anyInt())).thenReturn(empty);
        }
        return componentsFacade;
    }

    private static ComponentQuery emptyQuery() {
        ComponentQuery empty = mock(ComponentQuery.class);
        when(empty.withSpriteId(anyInt())).thenReturn(empty);
        when(empty.first()).thenReturn(null);
        return empty;
    }

    /** Scanning {@code iface} for {@code spriteId} finds one node at {@code spriteComp}. */
    private void stubSpriteOnBar(int iface, int spriteId, int spriteComp) {
        Components facade = components();
        ComponentQuery bar = barQueries.computeIfAbsent(iface, id -> {
            ComponentQuery q = emptyQuery();
            when(facade.in(id)).thenReturn(q);
            return q;
        });
        ComponentQuery hit = mock(ComponentQuery.class);
        ComponentNode node = mock(ComponentNode.class);
        when(bar.withSpriteId(spriteId)).thenReturn(hit);
        when(hit.first()).thenReturn(node);
        when(node.componentId()).thenReturn(spriteComp);
        when(node.interfaceId()).thenReturn(iface);
    }

    private List<GameAction> queuedActions(int expected) {
        ArgumentCaptor<GameAction> captor = ArgumentCaptor.forClass(GameAction.class);
        verify(api, times(expected)).queueAction(captor.capture());
        return captor.getAllValues();
    }

    private LocalPlayer playerWithMagic(int x, int y, int plane, int magicLevel) {
        Skill magic = new Skill(6, 0, magicLevel, magicLevel);
        return new LocalPlayer(0, 0, x, y, plane, 0, -1, -1, 0, -1, 0, false, -1,
                LocalPlayer.HEALTH_UNKNOWN, LocalPlayer.HEALTH_UNKNOWN, List.of(magic));
    }

    private LocalPlayer animatingPlayerWithMagic(int x, int y, int plane, int magicLevel) {
        Skill magic = new Skill(6, 0, magicLevel, magicLevel);
        return new LocalPlayer(0, 0, x, y, plane, 0, -1, SOME_ANIMATION, 0, -1, 0, false, -1,
                LocalPlayer.HEALTH_UNKNOWN, LocalPlayer.HEALTH_UNKNOWN, List.of(magic));
    }

    @Test
    void walkToFiresSurgeOnLongStraightChunkWhenMagicLevelIsHigh() {
        // Magic 99, player at (3000,3000), target 12 tiles due east (past
        // Dive's reach), no goal guard active (NO_GOAL). Surge's icon is on
        // iface 1670 at comp 165, and that icon component is what's clicked.
        when(snapshot.self()).thenReturn(playerWithMagic(3000, 3000, 0, 99));
        stubSurgeOnIface(1670, 165);

        bridge.walkTo(new WwTile(3012, 3000, 0));

        List<GameAction> actions = queuedActions(3);
        // Walk goes first so the engine orients the avatar before surge drains
        // off the queue.
        assertEquals(ActionTypes.WALK, actions.get(0).actionId());
        GameAction surge = actions.get(1);
        assertEquals(ActionTypes.COMPONENT, surge.actionId());
        assertEquals(1, surge.param1(), "option index");
        assertEquals(-1, surge.param2(), "no sub-slot");
        assertEquals((1670 << 16) | 165, surge.param3(), "(iface<<16)|icon_comp");
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
        // L-shaped offset (dx=10, dy=4): 4 tiles off the cardinal and 6 off the
        // diagonal, both past the 1/5 tolerance, so surge would waste the
        // cooldown moving off the path.
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
        // (< ABILITY_GOAL_GUARD = 12), surge would land us past the goal even
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
        verify(api, times(3)).queueAction(captor.capture());
        assertEquals(ActionTypes.COMPONENT, captor.getAllValues().get(1).actionId());
    }

    @Test
    void walkToSurgesOnLongNearCardinalHop() {
        // A randomized long click (dx=30, dy=5) is not exactly 8-way but is
        // within 1/5 of due east, so surge still fires.
        when(snapshot.self()).thenReturn(playerWithMagic(3000, 3000, 0, 99));
        stubSurgeOnIface(1670, 165);

        bridge.walkTo(new WwTile(3030, 3005, 0));

        ArgumentCaptor<GameAction> captor = ArgumentCaptor.forClass(GameAction.class);
        verify(api, times(3)).queueAction(captor.capture());
        assertEquals(ActionTypes.COMPONENT, captor.getAllValues().get(1).actionId());
    }

    @Test
    void isNearStraightAcceptsExactCardinals() {
        assertTrue(WorldWalkerCallbackBridge.isNearStraight(10, 0));
        assertTrue(WorldWalkerCallbackBridge.isNearStraight(-10, 0));
        assertTrue(WorldWalkerCallbackBridge.isNearStraight(0, 32));
        assertTrue(WorldWalkerCallbackBridge.isNearStraight(0, -8));
    }

    @Test
    void isNearStraightAcceptsExactDiagonals() {
        assertTrue(WorldWalkerCallbackBridge.isNearStraight(10, 10));
        assertTrue(WorldWalkerCallbackBridge.isNearStraight(-20, 20));
        assertTrue(WorldWalkerCallbackBridge.isNearStraight(32, -32));
    }

    @Test
    void isNearStraightAcceptsNearCardinalWithinTolerance() {
        // Minor axis at exactly major / 5 is the inclusive boundary.
        assertTrue(WorldWalkerCallbackBridge.isNearStraight(10, 2));
        assertTrue(WorldWalkerCallbackBridge.isNearStraight(-5, 30));
        assertFalse(WorldWalkerCallbackBridge.isNearStraight(10, 3));
    }

    @Test
    void isNearStraightAcceptsNearDiagonalWithinTolerance() {
        // Axes differing by exactly major / 5 is the inclusive boundary.
        assertTrue(WorldWalkerCallbackBridge.isNearStraight(30, 24));
        assertTrue(WorldWalkerCallbackBridge.isNearStraight(-26, -30));
        assertFalse(WorldWalkerCallbackBridge.isNearStraight(30, 23));
    }

    @Test
    void isNearStraightRejectsOffAxis() {
        // dx=10, dy=5 sits between east and north-east, past tolerance of both.
        assertFalse(WorldWalkerCallbackBridge.isNearStraight(10, 5));
        assertFalse(WorldWalkerCallbackBridge.isNearStraight(-10, 5));
        assertFalse(WorldWalkerCallbackBridge.isNearStraight(30, 15));
    }

    @Test
    void surgeScanLooksForTheSpriteItsStructNames() {
        // The icon comes from the cache struct, not a hard-coded id: the bar
        // is queried for exactly the struct's param-2802 sprite.
        when(snapshot.self()).thenReturn(playerWithMagic(3000, 3000, 0, 99));
        stubSurgeOnIface(1430, 40);

        bridge.walkTo(new WwTile(3020, 3000, 0));

        verify(api).getStructType(WorldWalkerCallbackBridge.STRUCT_SURGE);
        verify(barQueries.get(1430)).withSpriteId(SURGE_SPRITE);
        assertEquals((1430 << 16) | 40, queuedActions(3).get(1).param3());
    }

    @Test
    void surgeDoesNotFireWhenTheStructHasNoIcon() {
        // No cache (getStructType -> null): no sprite, so no bar is scanned.
        when(snapshot.self()).thenReturn(playerWithMagic(3000, 3000, 0, 99));

        bridge.walkTo(new WwTile(3020, 3000, 0));

        verify(api, times(1)).queueAction(any(GameAction.class));
        verify(api, never()).components();
    }

    @Test
    void surgeScanFindsTheIconOnAnyActionBar() {
        // Only bar 1671 carries Surge; the scan walks every bar to find it.
        when(snapshot.self()).thenReturn(playerWithMagic(3000, 3000, 0, 99));
        stubSurgeOnIface(1671, 7);

        bridge.walkTo(new WwTile(3020, 3000, 0));

        assertEquals((1671 << 16) | 7, queuedActions(3).get(1).param3());
    }

    @Test
    void surgeMissIsRescannedAfterBackoffNotLatched() {
        when(snapshot.self()).thenReturn(playerWithMagic(3000, 3000, 0, 99));
        stubStructSprite(WorldWalkerCallbackBridge.STRUCT_SURGE, SURGE_SPRITE);
        components();

        bridge.walkTo(new WwTile(3020, 3000, 0));
        // Within the backoff: no second scan, even though the bar now has it.
        stubSpriteOnBar(1672, SURGE_SPRITE, 9);
        clock.addAndGet(RESCAN_MS - 1);
        bridge.walkTo(new WwTile(3020, 3000, 0));
        verify(componentsFacade, times(1)).in(1672);
        // Past it: re-scanned, found, fired.
        clock.addAndGet(1);
        bridge.walkTo(new WwTile(3020, 3000, 0));

        verify(componentsFacade, times(2)).in(1672);
        List<GameAction> actions = queuedActions(5);
        assertEquals((1672 << 16) | 9, actions.get(3).param3());
    }

    @Test
    void surgeWaitsForItsCooldownVarc() {
        when(snapshot.self()).thenReturn(playerWithMagic(3000, 3000, 0, 99));
        stubSurgeOnIface(1670, 165);
        when(snapshot.gameCycle()).thenReturn(1_000);
        when(api.getVarcInt(WorldWalkerCallbackBridge.VARC_SURGE_COOLDOWN_END)).thenReturn(1_500);

        bridge.walkTo(new WwTile(3020, 3000, 0));
        verify(api, times(1)).queueAction(any(GameAction.class));

        when(snapshot.gameCycle()).thenReturn(1_500);
        bridge.walkTo(new WwTile(3020, 3000, 0));
        queuedActions(4);
    }

    @Test
    void surgeHonoursTheWallClockFloorEvenWhenTheVarcSaysReady() {
        // The varc always reads "ready" (0 <= cycle 0); only the floor stops a
        // second click a moment after the first.
        when(snapshot.self()).thenReturn(playerWithMagic(3000, 3000, 0, 99));
        stubSurgeOnIface(1670, 165);

        bridge.walkTo(new WwTile(3020, 3000, 0));
        clock.addAndGet(1_000);
        bridge.walkTo(new WwTile(3020, 3000, 0));

        List<GameAction> actions = queuedActions(4);
        assertEquals(ActionTypes.WALK, actions.get(3).actionId());
    }

    @Test
    void surgeCountsAFailedVarcReadAsOnCooldown() {
        when(snapshot.self()).thenReturn(playerWithMagic(3000, 3000, 0, 99));
        stubSurgeOnIface(1670, 165);
        when(api.getVarcInt(anyInt())).thenThrow(new IllegalStateException("pipe down"));

        bridge.walkTo(new WwTile(3020, 3000, 0));

        verify(api, times(1)).queueAction(any(GameAction.class));
    }

    @Test
    void failedChanceRollFiresNothing() {
        bridge = bridgeWithRng(NEVER_FIRE);
        when(snapshot.self()).thenReturn(playerWithMagic(3000, 3000, 0, 99));
        stubSurgeOnIface(1670, 165);

        bridge.walkTo(new WwTile(3020, 3000, 0));

        verify(api, times(1)).queueAction(any(GameAction.class));
    }

    @Test
    void diveSelectsTheBarSlotThenUsesItOnTheWalkTargetATickLater() {
        // Option 1 on a Dive slot is answered with Bladed Dive's melee-weapon
        // refusal; Dive is cast by selecting the slot and using it on a tile.
        when(snapshot.self()).thenReturn(player(3000, 3000, 0));
        stubStructSprite(WorldWalkerCallbackBridge.STRUCT_DIVE, DIVE_SPRITE);
        stubSpriteOnBar(1430, DIVE_SPRITE, 12);

        bridge.walkTo(new WwTile(3007, 3003, 0));

        List<GameAction> actions = queuedActions(4);
        assertEquals(ActionTypes.WALK, actions.get(0).actionId());
        GameAction select = actions.get(1);
        assertEquals(ActionTypes.SELECT_COMPONENT, select.actionId());
        assertEquals(0, select.param1());
        assertEquals(-1, select.param2());
        assertEquals((1430 << 16) | 12, select.param3());
        GameAction pick = actions.get(2);
        assertEquals(ActionTypes.SELECT_TILE, pick.actionId());
        assertEquals(0, pick.param1());
        assertEquals(3007, pick.param2());
        assertEquals(3003, pick.param3());
        assertEquals(ONE_TICK_MS, sleeps.get(0));
        assertTrue(actions.stream().noneMatch(a -> a.actionId() == ActionTypes.COMPONENT));
    }

    @Test
    void diveUsesNoTileWhenTheRunIsCancelledWhileTheSelectSettles() {
        bridge = new WorldWalkerCallbackBridge(api, () -> snapshot, cancel, events::add, NO_GOAL,
                WorldWalkerCallbackBridge.REQUIREMENT_VARPS,
                new WorldWalkerCallbackBridge.Pacing(ALWAYS_FIRE, clock::get,
                        ms -> cancel.set(true)));
        when(snapshot.self()).thenReturn(player(3000, 3000, 0));
        stubStructSprite(WorldWalkerCallbackBridge.STRUCT_DIVE, DIVE_SPRITE);
        stubSpriteOnBar(1430, DIVE_SPRITE, 12);

        bridge.walkTo(new WwTile(3007, 3003, 0));

        List<GameAction> actions = queuedActions(2);
        assertEquals(ActionTypes.SELECT_COMPONENT, actions.get(1).actionId());
    }

    @Test
    void bladedDiveIsPreferredOverDiveWhenBothAreBound() {
        when(snapshot.self()).thenReturn(player(3000, 3000, 0));
        stubStructSprite(WorldWalkerCallbackBridge.STRUCT_DIVE, DIVE_SPRITE);
        stubStructSprite(WorldWalkerCallbackBridge.STRUCT_BLADED_DIVE, BLADED_DIVE_SPRITE);
        stubSpriteOnBar(1430, DIVE_SPRITE, 12);
        stubSpriteOnBar(1673, BLADED_DIVE_SPRITE, 3);

        bridge.walkTo(new WwTile(3008, 3000, 0));

        assertEquals((1673 << 16) | 3, queuedActions(4).get(1).param3());
    }

    @Test
    void diveOnlyFiresWithinItsRange() {
        assertFalse(WorldWalkerCallbackBridge.isDiveHop(5, 0));
        assertTrue(WorldWalkerCallbackBridge.isDiveHop(6, 0));
        assertTrue(WorldWalkerCallbackBridge.isDiveHop(-10, 7));
        assertFalse(WorldWalkerCallbackBridge.isDiveHop(11, 0));

        when(snapshot.self()).thenReturn(player(3000, 3000, 0));
        stubStructSprite(WorldWalkerCallbackBridge.STRUCT_DIVE, DIVE_SPRITE);
        stubSpriteOnBar(1430, DIVE_SPRITE, 12);
        bridge.walkTo(new WwTile(3005, 3000, 0));
        bridge.walkTo(new WwTile(3011, 3000, 0));

        verify(api, times(2)).queueAction(any(GameAction.class));
    }

    @Test
    void diveIsPreferredOverSurgeOnAShortHop() {
        when(snapshot.self()).thenReturn(playerWithMagic(3000, 3000, 0, 99));
        stubSurgeOnIface(1670, 165);
        stubStructSprite(WorldWalkerCallbackBridge.STRUCT_DIVE, DIVE_SPRITE);
        stubSpriteOnBar(1430, DIVE_SPRITE, 12);

        bridge.walkTo(new WwTile(3010, 3000, 0));

        List<GameAction> actions = queuedActions(4);
        assertEquals((1430 << 16) | 12, actions.get(1).param3());
        assertEquals(ActionTypes.SELECT_TILE, actions.get(2).actionId());
    }

    @Test
    void surgeTakesAShortHopWhenDiveIsNotBound() {
        when(snapshot.self()).thenReturn(playerWithMagic(3000, 3000, 0, 99));
        stubSurgeOnIface(1670, 165);

        bridge.walkTo(new WwTile(3010, 3000, 0));

        assertEquals((1670 << 16) | 165, queuedActions(3).get(1).param3());
    }

    @Test
    void surgeReQueuesTheWalkAfterAOneTickPause() {
        // Surge cancels the walk in progress, so the same walk goes out again.
        // ALWAYS_FIRE's nextInt() is 0, which picks the 1-tick pause.
        when(snapshot.self()).thenReturn(playerWithMagic(3000, 3000, 0, 99));
        stubSurgeOnIface(1670, 165);

        bridge.walkTo(new WwTile(3020, 3004, 0));

        List<GameAction> actions = queuedActions(3);
        assertEquals(ActionTypes.COMPONENT, actions.get(1).actionId());
        GameAction rewalk = actions.get(2);
        assertEquals(ActionTypes.WALK, rewalk.actionId());
        assertEquals(3020, rewalk.param2());
        assertEquals(3004, rewalk.param3());
        assertEquals(List.of(ONE_TICK_MS), sleeps);
    }

    @Test
    void diveReQueuesTheWalkAfterATwoTickPause() {
        bridge = bridgeWithRng(TWO_TICK_PAUSE);
        when(snapshot.self()).thenReturn(player(3000, 3000, 0));
        stubStructSprite(WorldWalkerCallbackBridge.STRUCT_DIVE, DIVE_SPRITE);
        stubSpriteOnBar(1430, DIVE_SPRITE, 12);

        bridge.walkTo(new WwTile(3007, 3003, 0));

        List<GameAction> actions = queuedActions(4);
        assertEquals(ActionTypes.SELECT_TILE, actions.get(2).actionId());
        GameAction rewalk = actions.get(3);
        assertEquals(ActionTypes.WALK, rewalk.actionId());
        assertEquals(3007, rewalk.param2());
        assertEquals(3003, rewalk.param3());
        // One tick for the select to settle, then the re-walk pause.
        assertEquals(List.of(ONE_TICK_MS, 2 * ONE_TICK_MS), sleeps);
    }

    @Test
    void noReWalkWhenTheRunIsCancelledDuringThePause() {
        bridge = new WorldWalkerCallbackBridge(api, () -> snapshot, cancel, events::add, NO_GOAL,
                WorldWalkerCallbackBridge.REQUIREMENT_VARPS,
                new WorldWalkerCallbackBridge.Pacing(ALWAYS_FIRE, clock::get,
                        ms -> cancel.set(true)));
        when(snapshot.self()).thenReturn(playerWithMagic(3000, 3000, 0, 99));
        stubSurgeOnIface(1670, 165);

        bridge.walkTo(new WwTile(3020, 3000, 0));

        List<GameAction> actions = queuedActions(2);
        assertEquals(ActionTypes.COMPONENT, actions.get(1).actionId());
    }

    @Test
    void noAbilityFiresWhileThePlayerIsAnimating() {
        // Mid-animation (e.g. a lodestone arrival) the click would not cast.
        when(snapshot.self()).thenReturn(animatingPlayerWithMagic(3000, 3000, 0, 99));
        stubSurgeOnIface(1670, 165);
        stubStructSprite(WorldWalkerCallbackBridge.STRUCT_DIVE, DIVE_SPRITE);
        stubSpriteOnBar(1430, DIVE_SPRITE, 12);

        bridge.walkTo(new WwTile(3020, 3000, 0));
        bridge.walkTo(new WwTile(3008, 3000, 0));

        verify(api, times(2)).queueAction(any(GameAction.class));
        verify(api, never()).components();
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void diveRespectsTheGoalGuard() {
        WwGoal nearGoal = new WwGoal(3008, 3000, 0, 1);
        bridge = new WorldWalkerCallbackBridge(api, () -> snapshot, cancel, events::add, nearGoal,
                WorldWalkerCallbackBridge.REQUIREMENT_VARPS, pacing(ALWAYS_FIRE));
        when(snapshot.self()).thenReturn(player(3000, 3000, 0));
        stubStructSprite(WorldWalkerCallbackBridge.STRUCT_DIVE, DIVE_SPRITE);
        stubSpriteOnBar(1430, DIVE_SPRITE, 12);

        bridge.walkTo(new WwTile(3008, 3000, 0));

        verify(api, times(1)).queueAction(any(GameAction.class));
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
    void interactSkipsWhenLocBeyondFallbackReach() {
        // Five tiles from a 1x1 footprint is past the fallback reach of four, so
        // the loc is treated as absent and nothing is queued.
        Location other = new Location(1234, 99, -1, 3226, 3219, 0, 10, 0, 0);
        when(locationsTable.stream()).thenReturn(Stream.of(other));

        int issued = bridge.interact(1234, new WwTile(3221, 3219, 0), 0);

        assertEquals(0, issued);
        verify(api, never()).queueAction(any());
    }

    @Test
    void interactAcceptsLocWithinFallbackReach() {
        // A 1x1 loc three tiles off its row tile: outside the footprint reach of
        // one, inside the fallback reach of four, so it still resolves.
        Location near = new Location(1234, 99, -1, 3224, 3219, 0, 10, 0, 0);
        when(locationsTable.stream()).thenReturn(Stream.of(near));

        int issued = bridge.interact(1234, new WwTile(3221, 3219, 0), 0);

        assertEquals(1, issued);
        assertQueuedAt(1234, 3224, 3219);
    }

    @Test
    void interactResolvesMultiTileLocAnchoredAwayFromRowTile() {
        // Sabbot's cave entrance: a 4x4 loc anchored at (2857,3578) while the
        // dataset row names (2858,3576), two tiles south of the footprint.
        when(api.getLocationType(SABBOT_CAVE)).thenReturn(locType(SABBOT_CAVE, 4, 4));
        Location cave = new Location(SABBOT_CAVE, -1, -1, 2857, 3578, 0, 10, 0, 0);
        when(locationsTable.stream()).thenReturn(Stream.of(cave));

        int issued = bridge.interact(SABBOT_CAVE, new WwTile(2858, 3576, 0), 0);

        assertEquals(1, issued);
        assertQueuedAt(SABBOT_CAVE, 2857, 3578);
    }

    @Test
    void interactPrefersNearestFootprintOverNearestAnchor() {
        // A 1x3 loc turned a quarter (rotation 1) spans x 100..102 on y 200, so
        // row tile (103,200) touches it; an unturned twin at (105,200) spans
        // y 200..202 and sits two tiles off. The turned loc's anchor is further
        // (3 vs 2), so only a rotation-aware footprint picks it.
        when(api.getLocationType(1234)).thenReturn(locType(1234, 1, 3));
        Location turned = new Location(1234, -1, -1, 100, 200, 0, 10, 1, 0);
        Location upright = new Location(1234, -1, -1, 105, 200, 0, 10, 0, 0);
        when(locationsTable.stream()).thenReturn(Stream.of(upright, turned));

        bridge.interact(1234, new WwTile(103, 200, 0), 0);

        assertQueuedAt(1234, 100, 200);
    }

    @Test
    void interactNeverMatchesAcrossPlanes() {
        Location upstairs = new Location(1234, 99, -1, 3221, 3219, 1, 10, 0, 0);
        when(locationsTable.stream()).thenReturn(Stream.of(upstairs));

        int issued = bridge.interact(1234, new WwTile(3221, 3219, 0), 0);

        assertEquals(0, issued);
        verify(api, never()).queueAction(any());
    }

    @Test
    void interactAssumesSingleTileWhenCacheCannotAnswer() {
        when(api.getLocationType(1234)).thenThrow(new IllegalStateException("no cache"));
        Location exact = new Location(1234, 99, -1, 3221, 3219, 0, 10, 0, 0);
        when(locationsTable.stream()).thenReturn(Stream.of(exact));

        int issued = bridge.interact(1234, new WwTile(3221, 3219, 0), 0);

        assertEquals(1, issued);
        assertQueuedAt(1234, 3221, 3219);
    }

    private void assertQueuedAt(int objectId, int x, int y) {
        ArgumentCaptor<GameAction> captor = ArgumentCaptor.forClass(GameAction.class);
        verify(api).queueAction(captor.capture());
        GameAction action = captor.getValue();
        assertEquals(objectId, action.param1());
        assertEquals(x, action.param2());
        assertEquals(y, action.param3());
    }

    private static LocationType locType(int id, int sizeX, int sizeY) {
        return new LocationType(id, "", sizeX, sizeY, 0, 0, false, List.of(), -1, -1,
                List.of(), 0, Map.of());
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

    // ---- CLICK_NPC: the origin of a transition with no loc ----

    // Charter-ship crewmembers: Trader Crewmember type ids 4650..4656.
    private static final int CREW_FIRST_ID = 4650;
    private static final int CREW_LAST_ID  = 4656;
    private static final int CREW_RADIUS   = 8;

    private static Npc npc(int serverIndex, int typeId, int x, int y, int plane) {
        return new Npc(serverIndex, typeId, x, y, plane, 0, -1, -1, -1, 0, 0, -1);
    }

    private void runClickNpc(int option, int x, int y, int plane) {
        bridge.runChainStep(ChainStepKind.CLICK_NPC.wire(),
                option, x, y, plane, CREW_RADIUS, CREW_FIRST_ID, CREW_LAST_ID, 0, 0);
    }

    @Test
    void runChainStepClickNpcQueuesNpcOptionOnServerIndex() {
        when(npcsTable.stream()).thenReturn(Stream.of(npc(321, 4652, 2800, 3414, 0)));

        runClickNpc(0, 2801, 3414, 0);

        ArgumentCaptor<GameAction> captor = ArgumentCaptor.forClass(GameAction.class);
        verify(api).queueAction(captor.capture());
        GameAction action = captor.getValue();
        assertEquals(ActionTypes.NPC1, action.actionId(), "0-based option 0 is NPC_OPTIONS[1]");
        assertEquals(321, action.param1(), "server index in param1");
        assertEquals(0, action.param2());
        assertEquals(0, action.param3());
    }

    @Test
    void runChainStepClickNpcOptionIndexIsZeroBased() {
        when(npcsTable.stream()).thenReturn(Stream.of(npc(5, CREW_FIRST_ID, 100, 200, 0)));

        runClickNpc(2, 100, 200, 0);

        ArgumentCaptor<GameAction> captor = ArgumentCaptor.forClass(GameAction.class);
        verify(api).queueAction(captor.capture());
        assertEquals(ActionTypes.NPC3, captor.getValue().actionId());
    }

    @Test
    void runChainStepClickNpcPicksNearestInRange() {
        when(npcsTable.stream()).thenReturn(Stream.of(
                npc(1, CREW_LAST_ID, 106, 200, 0),
                npc(2, CREW_FIRST_ID, 102, 201, 0)));

        runClickNpc(0, 100, 200, 0);

        ArgumentCaptor<GameAction> captor = ArgumentCaptor.forClass(GameAction.class);
        verify(api).queueAction(captor.capture());
        assertEquals(2, captor.getValue().param1());
    }

    @Test
    void runChainStepClickNpcIgnoresWrongTypePlaneAndDistance() {
        when(npcsTable.stream()).thenReturn(Stream.of(
                npc(1, CREW_FIRST_ID - 1, 100, 200, 0),               // type below range
                npc(2, CREW_LAST_ID + 1, 100, 200, 0),                // type above range
                npc(3, CREW_FIRST_ID, 100, 200, 1),                   // other plane
                npc(4, CREW_FIRST_ID, 100 + CREW_RADIUS + 1, 200, 0))); // beyond radius

        runClickNpc(0, 100, 200, 0);

        verify(api, never()).queueAction(any());
    }

    @Test
    void runChainStepClickNpcDoesNothingWhenNoNpc() {
        runClickNpc(0, 100, 200, 0);

        verify(api, never()).queueAction(any());
    }

    @Test
    void runChainStepClickNpcDoesNothingForOutOfRangeOption() {
        when(npcsTable.stream()).thenReturn(Stream.of(npc(5, CREW_FIRST_ID, 100, 200, 0)));

        runClickNpc(ActionTypes.NPC_OPTIONS.length - 1, 100, 200, 0);

        verify(api, never()).queueAction(any());
    }

    // ---- DIALOGUE_ANSWER: pick an open option by text ----

    // The option list's options container and two option rows (with the label
    // leaf and "N." number cell hanging under each), as get_interface_tree
    // answers for interface 1188.
    private static final int OPTIONS_CONTAINER = 0;
    private static final int FIRST_ROW = 8;
    private static final int SECOND_ROW = 13;
    private static final int NO_PARENT = -1;
    private static final int CONTAINER_INDEX = 0;
    private static final int FIRST_ROW_INDEX = 1;
    private static final int SECOND_ROW_INDEX = 4;

    private void openOptionList(String first, String second) {
        int iface = Dialog.MULTI_CHOICE_INTERFACE;
        when(api.components()).thenReturn(new Components(api));
        when(api.getInterfaceTree(iface, OPTIONS_CONTAINER)).thenReturn(List.of(
                treeNode(iface, OPTIONS_CONTAINER, ComponentType.LAYER, null, NO_PARENT),
                treeNode(iface, FIRST_ROW, ComponentType.LAYER, null, CONTAINER_INDEX),
                treeNode(iface, FIRST_ROW + 1, ComponentType.TEXT, "1.", FIRST_ROW_INDEX),
                treeNode(iface, FIRST_ROW + 2, ComponentType.TEXT, first, FIRST_ROW_INDEX),
                treeNode(iface, SECOND_ROW, ComponentType.LAYER, null, CONTAINER_INDEX),
                treeNode(iface, SECOND_ROW + 1, ComponentType.TEXT, "2.", SECOND_ROW_INDEX),
                treeNode(iface, SECOND_ROW + 2, ComponentType.TEXT, second, SECOND_ROW_INDEX)));
    }

    private static ComponentTreeNode treeNode(int iface, int comp, ComponentType type,
                                              String text, int parentIndex) {
        return new ComponentTreeNode(new Component(
                iface, comp, -1, 0, type.code(),
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                text, 0, -1, -1, -1, List.of()), parentIndex);
    }

    /** The answer packed as the executor sends it: UTF-8, little-endian slots, zero-padded. */
    private static int[] packAnswer(String text) {
        ByteBuffer buffer = ByteBuffer.allocate(DialogueAnswerText.MAX_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(text.getBytes(StandardCharsets.UTF_8));
        buffer.rewind();
        int[] slots = new int[DialogueAnswerText.SLOT_COUNT];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = buffer.getInt();
        }
        return slots;
    }

    private void runAnswer(String text) {
        int[] s = packAnswer(text);
        bridge.runChainStep(ChainStepKind.DIALOGUE_ANSWER.wire(),
                s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], s[8]);
    }

    @Test
    void runChainStepDialogueAnswerSelectsOptionContainingAnswer() {
        openOptionList("No thanks.", "<col=ffffff>YES, take me there.</col>");

        runAnswer("yes, take me");

        ArgumentCaptor<GameAction> captor = ArgumentCaptor.forClass(GameAction.class);
        verify(api).queueAction(captor.capture());
        GameAction action = captor.getValue();
        assertEquals(ActionTypes.DIALOGUE, action.actionId());
        assertEquals((Dialog.MULTI_CHOICE_INTERFACE << 16) | SECOND_ROW, action.param3(),
                "the matching option's row is selected, not its label leaf");
    }

    @Test
    void runChainStepDialogueAnswerPicksFirstOfSeveralMatches() {
        openOptionList("Yes.", "Yes please.");

        runAnswer("Yes");

        ArgumentCaptor<GameAction> captor = ArgumentCaptor.forClass(GameAction.class);
        verify(api).queueAction(captor.capture());
        assertEquals((Dialog.MULTI_CHOICE_INTERFACE << 16) | FIRST_ROW, captor.getValue().param3());
    }

    @Test
    void runChainStepDialogueAnswerDoesNothingWhenNoOptionMatches() {
        openOptionList("No thanks.", "Maybe later.");

        runAnswer("Yes.");

        verify(api, never()).queueAction(any());
    }

    @Test
    void runChainStepDialogueAnswerDoesNothingWhenNoListIsOpen() {
        when(api.components()).thenReturn(new Components(api));
        when(api.getInterfaceTree(Dialog.MULTI_CHOICE_INTERFACE, OPTIONS_CONTAINER))
                .thenReturn(List.of());

        runAnswer("Yes.");

        verify(api, never()).queueAction(any());
    }

    @Test
    void runChainStepDialogueAnswerIgnoresEmptyAnswer() {
        openOptionList("No thanks.", "Yes.");

        runAnswer("");

        verify(api, never()).queueAction(any());
    }

    @Test
    void sleepTicksSleepsApproxSixHundredMsPerTick() throws Exception {
        // The default constructor sleeps for real (Pacing.system()).
        bridge = new WorldWalkerCallbackBridge(api, () -> snapshot, cancel, events::add, NO_GOAL);
        long start = System.nanoTime();
        bridge.sleepTicks(2);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMs >= 1100, "expected >= 1100ms, got " + elapsedMs);
        assertTrue(elapsedMs < 2000, "expected < 2000ms, got " + elapsedMs);
    }

    @Test
    void sleepTicksSleepsThroughTheInjectedSleeper() {
        bridge.sleepTicks(2);

        assertEquals(List.of(2 * ONE_TICK_MS), sleeps);
    }

    @Test
    void sleepTicksZeroReturnsImmediately() {
        bridge.sleepTicks(0);

        assertTrue(sleeps.isEmpty());
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
