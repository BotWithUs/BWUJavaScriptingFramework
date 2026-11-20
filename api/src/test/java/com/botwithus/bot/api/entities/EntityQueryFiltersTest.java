package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.model.NpcType;
import com.botwithus.bot.api.snapshot.Orientation;
import com.botwithus.bot.api.world.Coordinate;
import com.botwithus.bot.api.world.RectangularArea;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The named filters and terminals added to {@link EntityQuery} and its mixins, run through
 * the real {@link Npcs}, {@link Players} and {@link SceneObjects} query paths.
 */
class EntityQueryFiltersTest {

    private static final int GUARD = 3_010;
    private static final int COW = 81;
    private static final int RAT = 47;
    private static final int ATTACK_ANIM = 422;
    private static final int X = 3200;
    private static final int Y = 3200;

    private final SceneFixture scene = new SceneFixture().withNpcs(
            SceneFixture.npc(0, GUARD, X, Y, 0, ATTACK_ANIM, 20, 40),
            SceneFixture.npc(1, COW, X + 5, Y, SceneFixture.FLAG_MOVING, -1, 8, 8),
            SceneFixture.npc(2, RAT, X + 10, Y + 10, 0, -1, 2, 0));
    private final Npcs npcs = new Npcs(scene.api);

    EntityQueryFiltersTest() {
        when(scene.api.getNpcType(GUARD)).thenReturn(type(GUARD, "Guard", 21, "Attack", "Pickpocket"));
        when(scene.api.getNpcType(COW)).thenReturn(type(COW, "Cow", 2, "Attack"));
        when(scene.api.getNpcType(RAT)).thenReturn(type(RAT, "Giant rat", 3, "Attack"));
    }

    @Test
    void animationFilters() {
        assertEquals(List.of(GUARD), ids(npcs.query().animating().all()));
        assertEquals(List.of(COW, RAT), ids(npcs.query().notAnimating().all()));
        assertEquals(List.of(GUARD), ids(npcs.query().withAnimation(1, ATTACK_ANIM).all()));
        assertEquals(List.of(), ids(npcs.query().withAnimation().all()));
    }

    @Test
    void movementAndCombatLevelFilters() {
        assertEquals(List.of(COW), ids(npcs.query().moving().all()));
        assertEquals(List.of(GUARD, RAT), ids(npcs.query().notMoving().all()));
        assertEquals(List.of(COW, RAT), ids(npcs.query().withCombatLevel(1, 3).all()));
    }

    @Test
    void healthFilters_percentSkipsUnknownMaximum() {
        assertEquals(List.of(GUARD, COW), ids(npcs.query().withHealth(8, 20).all()));
        assertEquals(List.of(GUARD), ids(npcs.query().withHealthPercent(0, 50).all()));
        assertEquals(List.of(COW), ids(npcs.query().withHealthPercent(100, 100).all()));
    }

    @Test
    void optionFilter_matchesIgnoringCase() {
        assertEquals(List.of(GUARD), ids(npcs.query().withOption("pickpocket").all()));
    }

    @Test
    void idsAndNamesVarargs() {
        assertEquals(List.of(GUARD, RAT), ids(npcs.query().withIds(RAT, GUARD).all()));
        assertEquals(List.of(COW, RAT), ids(npcs.query().namedAnyOf("cow", "GIANT RAT").all()));
        assertEquals(List.of(), ids(npcs.query().namedAnyOf("Giant").all()), "whole names only");
    }

    @Test
    void insideAndOutsideArea() {
        RectangularArea pen = RectangularArea.of(new Coordinate(X, Y, 0), new Coordinate(X + 5, Y + 5, 0));
        assertEquals(List.of(GUARD, COW), ids(npcs.query().inside(pen).all()));
        assertEquals(List.of(RAT), ids(npcs.query().outside(pen).all()));
    }

    @Test
    void reachable_asksThePathfinderPerCandidateOnThePlayersPlane() {
        scene.withLocalPlayerAt(X, Y, 0);
        when(scene.api.isReachable(X + 5, Y)).thenReturn(true);
        assertEquals(List.of(COW), ids(npcs.query().reachable().all()));
    }

    @Test
    void reachable_playerNotLoaded_matchesNothingWithoutPathfinding() {
        assertEquals(List.of(), ids(npcs.query().reachable().all()));
        verify(scene.api, never()).isReachable(anyInt(), anyInt());
    }

    @Test
    void terminals_lastRandomNearestTo() {
        assertEquals(RAT, npcs.query().last().typeId());
        assertEquals(RAT, npcs.query().nearestTo(X + 9, Y + 9).typeId());
        assertEquals(COW, npcs.query().nearestTo(new Coordinate(X + 6, Y, 0)).typeId());
        assertNull(npcs.query().nearestTo(X, Y, 1), "nothing on plane 1");
        assertNull(npcs.query().withId(-5).last());

        Set<Integer> drawn = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            drawn.add(npcs.query().random().typeId());
        }
        assertEquals(Set.of(GUARD, COW, RAT), drawn);
    }

    @Test
    void playerQuery_hasActorFilters() {
        scene.withPlayers(
                player(0, ATTACK_ANIM, SceneFixture.FLAG_MOVING),
                player(1, -1, 0));
        Players players = new Players(scene.api);
        assertEquals(List.of(0), indices(players.query().animating().all()));
        assertEquals(List.of(1), indices(players.query().notMoving().all()));
    }

    @Test
    void sceneObjectQuery_hasAnimationAndOptionFilters() {
        scene.withLocations(
                SceneFixture.loc(GUARD, X, Y, ATTACK_ANIM, 0),
                SceneFixture.loc(COW, X + 1, Y, -1, 0));
        when(scene.api.getLocationType(anyInt())).thenReturn(null);
        SceneObjects objects = new SceneObjects(scene.api);
        List<SceneObject> animating = objects.query().animating().all();
        assertEquals(1, animating.size());
        assertTrue(animating.getFirst().isAnimating());
        assertEquals(List.of(), objects.query().withOption("Open").all(), "no definition, no options");
    }

    // FQN: the snapshot Player shares its simple name with this package's entity Player,
    // the same collision the production Player wrapper documents.
    private static com.botwithus.bot.api.snapshot.Player player(int index, int animationId, int flags) {
        return new com.botwithus.bot.api.snapshot.Player(index, X, Y, 0, flags, -1, animationId, -1,
                3, -1, Orientation.unknown(), "p" + index, -1, -1, -1);
    }

    private static NpcType type(int id, String name, int combatLevel, String... options) {
        return new NpcType(id, name, combatLevel, true, true, List.of(options), -1, -1, List.of(), Map.of());
    }

    private static List<Integer> ids(List<Npc> list) {
        return list.stream().map(Npc::typeId).toList();
    }

    private static List<Integer> indices(List<Player> list) {
        return list.stream().map(Player::serverIndex).toList();
    }
}
