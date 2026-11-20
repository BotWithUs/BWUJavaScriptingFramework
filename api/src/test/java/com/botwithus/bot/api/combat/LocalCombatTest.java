package com.botwithus.bot.api.combat;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.event.EventBus;
import com.botwithus.bot.api.event.HitmarkEvent;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.model.EntityKind;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.snapshot.Npc;
import com.botwithus.bot.api.snapshot.Orientation;
import com.botwithus.bot.api.snapshot.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link LocalCombat} and {@link HitmarkHistory} over a mocked snapshot. The own index (7),
 * target index (321) and the NPC/player rows are distinct, so resolving the target through
 * the wrong table or the wrong index fails.
 */
class LocalCombatTest {

    private static final int OWN_INDEX = 7;
    private static final int TARGET_INDEX = 321;

    private final GameAPI api = mock(GameAPI.class);
    private final GameSnapshot snapshot = mock(GameSnapshot.class);
    private final GameSnapshot.Npcs npcs = mock(GameSnapshot.Npcs.class);
    private final GameSnapshot.Players players = mock(GameSnapshot.Players.class);
    private final LocalCombat combat = new LocalCombat(api);

    @BeforeEach
    void setUp() {
        when(api.gamevals()).thenReturn(GamevalIndex.empty());
        when(api.snapshot()).thenReturn(snapshot);
        when(snapshot.npcs()).thenReturn(npcs);
        when(snapshot.players()).thenReturn(players);
        when(snapshot.ownIndex()).thenReturn(OWN_INDEX);
        when(npcs.byServerIndex(TARGET_INDEX)).thenReturn(Optional.of(new Npc(TARGET_INDEX, 3078, 3201, 3201, 0, 0, OWN_INDEX, -1, -1, 50, 100, -1)));
        when(players.byServerIndex(TARGET_INDEX)).thenReturn(Optional.empty());
    }

    @Test
    void target_npc_resolvesTheNpcRow() {
        when(snapshot.self()).thenReturn(self(TARGET_INDEX, LocalCombat.TARGET_TYPE_NPC));

        assertInstanceOf(CombatTarget.NpcTarget.class, combat.target());
    }

    @Test
    void target_playerNotLoaded_isUnresolvedNotNone() {
        when(snapshot.self()).thenReturn(self(TARGET_INDEX, LocalCombat.TARGET_TYPE_PLAYER));

        CombatTarget.Unresolved target = assertInstanceOf(CombatTarget.Unresolved.class, combat.target());
        assertEquals(TARGET_INDEX, target.targetIndex());
    }

    @Test
    void target_noneWhenIndexIsMinusOne() {
        when(snapshot.self()).thenReturn(self(-1, 0));

        assertSame(CombatTarget.NONE, combat.target());
        assertFalse(combat.isInCombat());
    }

    @Test
    void target_theTwoIdleEncodingsSeenLive_areBothNone() {
        when(snapshot.self()).thenReturn(self(0, LocalCombat.TARGET_TYPE_NEVER_SET));
        assertSame(CombatTarget.NONE, combat.target());
        when(snapshot.self()).thenReturn(self(0, LocalCombat.TARGET_TYPE_CLEARED));
        assertSame(CombatTarget.NONE, combat.target());
        assertFalse(combat.isInCombat());
    }

    @Test
    void inCombat_trueOnTheGamesFlagAlone() {
        when(snapshot.self()).thenReturn(self(-1, 0));
        when(api.getVarbit(1899)).thenReturn(1);

        assertTrue(combat.isInCombat());
    }

    @Test
    void inCombat_trueOnARecentHitOnSelf_withNoFlagOrTarget() {
        when(snapshot.self()).thenReturn(self(-1, 0));
        AtomicLong now = new AtomicLong(100_000L);
        HitmarkHistory hits = new HitmarkHistory(mock(EventBus.class), now::get);
        hits.record(new HitmarkEvent(OWN_INDEX, HitmarkEvent.TARGET_PLAYER, 1, 120, 0, 99_000L));

        assertTrue(combat.isInCombat(hits));
        now.set(99_000L + LocalCombat.RECENT_HIT_MS + 1);
        assertFalse(combat.isInCombat(hits));
    }

    @Test
    void name_comesFromTheOwnPlayerRow() {
        when(players.byServerIndex(OWN_INDEX)).thenReturn(Optional.of(new Player(OWN_INDEX, 3200,
                3200, 0, 0, -1, -1, -1, 99, -1, Orientation.unknown(), "Zezima")));

        assertEquals(Optional.of("Zezima"), combat.name());
    }

    @Test
    void lifePoints_preferTheSnapshot() {
        when(snapshot.self()).thenReturn(self(-1, 0));

        assertEquals(812, combat.lifePoints());
        assertEquals(990, combat.maxLifePoints());
    }

    @Test
    void hitmarkHistory_keepsEntitiesApart_capsAndExpires() {
        AtomicLong now = new AtomicLong(1_000_000L);
        HitmarkHistory hits = new HitmarkHistory(mock(EventBus.class), now::get);
        for (int i = 0; i < HitmarkHistory.PER_ENTITY_CAPACITY + 4; i++) {
            hits.record(new HitmarkEvent(TARGET_INDEX, HitmarkEvent.TARGET_NPC, 1, i, 0, now.get()));
        }
        hits.record(new HitmarkEvent(TARGET_INDEX, HitmarkEvent.TARGET_PLAYER, 1, 500, 0, now.get()));

        List<HitmarkEvent> onNpc = hits.recent(EntityKind.NPC, TARGET_INDEX);
        assertEquals(HitmarkHistory.PER_ENTITY_CAPACITY, onNpc.size());
        assertEquals(4, onNpc.getFirst().damage());
        assertEquals(500, hits.damageWithin(EntityKind.PLAYER, TARGET_INDEX, 1_000));
        now.addAndGet(HitmarkHistory.RETENTION_MS + 1);
        assertTrue(hits.recent(EntityKind.NPC, TARGET_INDEX).isEmpty());
    }

    private static LocalPlayer self(int targetIndex, int targetType) {
        return new LocalPlayer(OWN_INDEX, 90, 3200, 3200, 0, 0, -1, -1, -1, targetIndex,
                targetType, true, -1, 812, 990, List.of());
    }
}
