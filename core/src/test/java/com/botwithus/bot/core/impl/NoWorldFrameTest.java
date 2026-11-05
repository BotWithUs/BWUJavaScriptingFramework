package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.entities.Npc;
import com.botwithus.bot.api.entities.Projectile;
import com.botwithus.bot.api.event.GameEvent;
import com.botwithus.bot.api.event.WalkArrivedEvent;
import com.botwithus.bot.api.event.WalkFailedEvent;
import com.botwithus.bot.api.model.QuestType;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.core.impl.snapshot.NoWorldFrame;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.worldwalker.CapabilitySnapshot;
import com.botwithus.bot.core.worldwalker.DisabledMoves;
import com.botwithus.bot.core.worldwalker.WorldWalker;
import com.botwithus.bot.core.worldwalker.WwGoal;
import com.botwithus.bot.core.worldwalker.WwStatus;
import com.botwithus.bot.core.worldwalker.WwTile;
import com.botwithus.bot.quest.QuestId;
import com.botwithus.bot.quest.QuestRequirements;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every site that reads the self block or treats the player as present, run over the bytes of each
 * frame with no local player. These states are hard to reach on a live client, so the bytes are the
 * proof.
 */
class NoWorldFrameTest {

    private static final int MAGIC_SKILL = 6;
    private static final int WALK_X = 3200;
    private static final int WALK_Y = 3201;
    private static final int QUEST_ID = 1;
    private static final String WALK_OWNER = "test";

    private final RpcClient rpc = mock(RpcClient.class);
    private final AtomicReference<GameSnapshot> frame = new AtomicReference<>();
    private final GameAPIImpl api = new GameAPIImpl(rpc, null, frame::get);

    @ParameterizedTest
    @EnumSource(NoWorldFrame.class)
    void self_isAbsent(NoWorldFrame noWorld) {
        assertNull(noWorld.snapshot().self());
    }

    /** The Java host has no self flag on a player row, so the row table has nothing to get wrong. */
    @ParameterizedTest
    @EnumSource(NoWorldFrame.class)
    void players_holdNoRowForTheOwnIndex(NoWorldFrame noWorld) {
        GameSnapshot snapshot = noWorld.snapshot();

        assertEquals(0, snapshot.players().count());
        assertTrue(snapshot.players().byServerIndex(snapshot.ownIndex()).isEmpty());
    }

    @ParameterizedTest
    @EnumSource(NoWorldFrame.class)
    void getLocalPlayer_isAbsent_andSpendsNoRoundTripOnHealth(NoWorldFrame noWorld) throws Exception {
        frame.set(noWorld.snapshot());
        clearInvocations(rpc);

        assertNull(api.getLocalPlayer());
        verify(rpc, never()).callSync(any(), any());
    }

    @ParameterizedTest
    @EnumSource(NoWorldFrame.class)
    void getPlayerStat_isAbsent(NoWorldFrame noWorld) {
        frame.set(noWorld.snapshot());

        assertNull(api.getPlayerStat(MAGIC_SKILL));
    }

    /** A zeroed self block reads as not a member, which would plan a free-to-play route. */
    @ParameterizedTest
    @EnumSource(NoWorldFrame.class)
    void walkMoves_isNotRestrictedByAPlayerThatIsNotThere(NoWorldFrame noWorld) {
        frame.set(noWorld.snapshot());
        api.setRestrictFreeToPlay(true);

        assertEquals(DisabledMoves.NONE, api.walkMoves());
    }

    @ParameterizedTest
    @EnumSource(NoWorldFrame.class)
    void pathQueries_fromSelfReportNoRoute(NoWorldFrame noWorld) {
        frame.set(noWorld.snapshot());

        assertFalse(api.findPath(WALK_X, WALK_Y).found());
        assertFalse(api.isReachable(WALK_X, WALK_Y, 0));
    }

    @ParameterizedTest
    @EnumSource(NoWorldFrame.class)
    void walk_failsWithoutRunningTheExecutor(NoWorldFrame noWorld) {
        frame.set(noWorld.snapshot());
        WorldWalker walker = mock(WorldWalker.class);
        when(walker.runExecutor(any(), any(), any())).thenReturn(WwStatus.ARRIVED);

        List<GameEvent> events = runWalk(walker);

        verify(walker, never()).runExecutor(any(), any(), any());
        assertEquals(List.of(WalkFailedEvent.class), eventTypes(events));
    }

    @ParameterizedTest
    @EnumSource(NoWorldFrame.class)
    void retainedNpc_hasNoDistanceToAPlayerThatIsNotThere(NoWorldFrame noWorld) {
        frame.set(NoWorldFrame.inWorldAtIndexZero(0));
        Npc npc = api.npcs().query().first();
        assertNotNull(npc);

        frame.set(noWorld.snapshot());

        assertEquals(Integer.MAX_VALUE, npc.distanceToPlayer());
    }

    @ParameterizedTest
    @EnumSource(NoWorldFrame.class)
    void retainedProjectile_doesNotTargetAPlayerThatIsNotThere(NoWorldFrame noWorld) {
        GameSnapshot snapshot = noWorld.snapshot();
        frame.set(NoWorldFrame.inWorldAtIndexZero(Math.max(snapshot.ownIndex(), 0)));
        Projectile projectile = api.projectiles().all().getFirst();

        frame.set(snapshot);

        assertFalse(projectile.targetsLocalPlayer());
    }

    /** The position callback has no way to say absent, so it answers a tile nothing can mistake. */
    @ParameterizedTest
    @EnumSource(NoWorldFrame.class)
    void callbackBridge_readsTheNoPlayerAnswers(NoWorldFrame noWorld) {
        GameSnapshot snapshot = noWorld.snapshot();
        WorldWalkerCallbackBridge bridge = new WorldWalkerCallbackBridge(
                mock(GameAPI.class), () -> snapshot, new AtomicBoolean(), event -> { }, null);

        assertEquals(new WwTile(0, 0, 0), bridge.readPosition());
        assertEquals(CapabilitySnapshot.empty(), bridge.readCapability());
    }

    /** A zeroed self block reads as not a member, which would fail a members quest's check. */
    @ParameterizedTest
    @EnumSource(NoWorldFrame.class)
    void questRequirements_doNotReportMembershipMissing(NoWorldFrame noWorld) {
        GameAPI gameApi = mock(GameAPI.class);
        QuestType quest = mock(QuestType.class);
        when(quest.membersOnly()).thenReturn(true);
        when(quest.skillRequirements()).thenReturn(List.of());
        when(gameApi.getQuestType(QUEST_ID)).thenReturn(quest);
        when(gameApi.snapshot()).thenReturn(noWorld.snapshot());

        assertTrue(QuestRequirements.check(new QuestId(QUEST_ID, "quest", List.of()), gameApi).ok());
    }

    @Test
    void inWorldAtIndexZero_isAPlayer() {
        frame.set(NoWorldFrame.inWorldAtIndexZero(0));

        LocalPlayer self = frame.get().self();

        assertNotNull(self);
        assertEquals(0, self.serverIndex());
        assertEquals(NoWorldFrame.TILE_X, self.tileX());
        assertEquals(NoWorldFrame.TILE_Y, self.tileY());
        assertEquals(NoWorldFrame.PLANE, self.plane());
        assertEquals(NoWorldFrame.SERVER_TILE_X, self.serverTileX());
        assertEquals(NoWorldFrame.SERVER_TILE_Y, self.serverTileY());
        assertEquals(NoWorldFrame.PLANE, self.serverPlane());
        assertEquals(NoWorldFrame.RUN_ENERGY, self.runEnergy());
        assertEquals(NoWorldFrame.RUN_WEIGHT, self.runWeight());
    }

    @Test
    void inWorldAtIndexZero_everySiteSeesThePlayer() {
        frame.set(NoWorldFrame.inWorldAtIndexZero(0));
        api.setRestrictFreeToPlay(true);
        WorldWalkerCallbackBridge bridge = new WorldWalkerCallbackBridge(
                mock(GameAPI.class), frame::get, new AtomicBoolean(), event -> { }, null);

        assertNotNull(api.getLocalPlayer());
        assertEquals(DisabledMoves.FREE_TO_PLAY, api.walkMoves());
        assertEquals(new WwTile(NoWorldFrame.TILE_X, NoWorldFrame.TILE_Y, NoWorldFrame.PLANE),
                bridge.readPosition());
        assertTrue(api.projectiles().all().getFirst().targetsLocalPlayer());
        assertEquals(NoWorldFrame.TILE_Y - NoWorldFrame.NPC_TILE_Y,
                api.npcs().query().first().distanceToPlayer());
    }

    @Test
    void inWorldAtIndexZero_walkRunsTheExecutor() {
        frame.set(NoWorldFrame.inWorldAtIndexZero(0));
        WorldWalker walker = mock(WorldWalker.class);
        when(walker.runExecutor(any(), any(), any())).thenReturn(WwStatus.ARRIVED);

        List<GameEvent> events = runWalk(walker);

        verify(walker).runExecutor(any(), any(), any());
        assertEquals(List.of(WalkArrivedEvent.class), eventTypes(events));
    }

    private List<GameEvent> runWalk(WorldWalker walker) {
        List<GameEvent> events = new ArrayList<>();
        WalkLease lease = api.acquireWalkLease(WALK_OWNER, WALK_X, WALK_Y);
        api.runWalk(walker, new WwGoal(WALK_X, WALK_Y, 0, 0), DisabledMoves.NONE,
                mock(WorldWalkerCallbackBridge.class), lease, events::add, WALK_X, WALK_Y);
        return events;
    }

    private static List<Class<?>> eventTypes(List<GameEvent> events) {
        return events.stream().<Class<?>>map(GameEvent::getClass).toList();
    }
}
