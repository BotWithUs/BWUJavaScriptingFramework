package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.entities.HintArrows;
import com.botwithus.bot.api.model.EntityRead;
import com.botwithus.bot.api.model.EntityReadStatus;
import com.botwithus.bot.api.model.HintArrow;
import com.botwithus.bot.api.model.HintArrowType;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The {@link HintArrows.Query} filters and terminals over a canned arrow list. */
class HintArrowsQueryTest {

    private static final int NPC_INDEX = 42;
    private static final int PLAYER_INDEX = 7;

    /** An entity arrow whose tile fields hold a number close to the player, to catch a misuse of them. */
    private static final HintArrow ON_NPC = arrow(HintArrowType.NPC, NPC_INDEX, 0, 3201, 3201);
    private static final HintArrow ON_PLAYER = arrow(HintArrowType.PLAYER, PLAYER_INDEX, 0, 3202, 3202);
    private static final HintArrow FAR_TILE = arrow(HintArrowType.TILE_CORNER, -1, 0, 3250, 3250);
    private static final HintArrow NEAR_TILE = arrow(HintArrowType.TILE_CENTRE, -1, 0, 3210, 3210);
    private static final HintArrow UPSTAIRS = arrow(HintArrowType.TILE_NORTH, -1, 1, 3205, 3205);
    private static final List<HintArrow> ALL = List.of(ON_NPC, ON_PLAYER, FAR_TILE, NEAR_TILE, UPSTAIRS);

    private GameAPI api;

    @BeforeEach
    void stub() {
        api = mock(GameAPI.class);
        when(api.queryHintArrows()).thenReturn(EntityRead.ok(ALL, false));
        when(api.getLocalPlayer()).thenReturn(selfAt(3200, 3200, 0));
    }

    @Test
    void noFilters_returnsEverythingInOrder() {
        assertEquals(ALL, query().all());
        assertEquals(ALL.size(), query().count());
        assertEquals(ON_NPC, query().first());
        assertTrue(query().exists());
    }

    @Test
    void ofType_matchesAnyNamedType() {
        assertEquals(List.of(FAR_TILE), query().ofType(HintArrowType.TILE_CORNER).all());
        assertEquals(List.of(ON_NPC, ON_PLAYER),
                query().ofType(HintArrowType.NPC, HintArrowType.PLAYER, HintArrowType.NPC).all());
        assertTrue(query().ofType().all().isEmpty());
    }

    @Test
    void targeting_needsTheRightKindAndTheRightIndex() {
        assertEquals(List.of(ON_NPC), query().targetingNpc(NPC_INDEX).all());
        assertEquals(List.of(ON_PLAYER), query().targetingPlayer(PLAYER_INDEX).all());
        assertTrue(query().targetingNpc(PLAYER_INDEX).all().isEmpty(), "a player's index is not an NPC's");
        assertTrue(query().targetingPlayer(NPC_INDEX).all().isEmpty());
    }

    @Test
    void onTile_matchesATileArrowExactly() {
        assertEquals(List.of(NEAR_TILE), query().onTile(3210, 3210).all());
        assertTrue(query().onTile(3210, 3211).all().isEmpty());
    }

    @Test
    void positionFilters_neverMatchAnEntityArrow() {
        assertTrue(query().onTile(3201, 3201).all().isEmpty(), "an NPC arrow's tile fields are not a tile");
        assertEquals(List.of(NEAR_TILE, UPSTAIRS), query().near(3200, 3200, 10).all());
    }

    @Test
    void onPlane_filtersByPlane() {
        assertEquals(List.of(UPSTAIRS), query().onPlane(1).all());
    }

    @Test
    void nearPlayer_staysOnThePlayersPlane() {
        assertEquals(List.of(NEAR_TILE), query().nearPlayer(10).all());
        assertEquals(List.of(FAR_TILE, NEAR_TILE), query().nearPlayer().all());
    }

    @Test
    void nearPlayer_withNoLocalPlayer_matchesNothingRatherThanEverything() {
        when(api.getLocalPlayer()).thenReturn(null);

        assertTrue(query().nearPlayer().all().isEmpty());
    }

    @Test
    void nearest_measuresFromThePlayerAndSkipsArrowsWithNoTile() {
        assertEquals(UPSTAIRS, query().nearest());
        assertEquals(NEAR_TILE, query().onPlane(0).nearest());
        assertNull(query().ofType(HintArrowType.NPC).nearest(), "an NPC arrow has no tile to be nearest by");
    }

    @Test
    void nearest_prefersTheNearCentre() {
        assertEquals(FAR_TILE, query().near(3299, 3299, 500).nearest());
    }

    @Test
    void nearest_withNoCentreAndNoLocalPlayer_isNull() {
        when(api.getLocalPlayer()).thenReturn(null);

        assertNull(query().nearest());
        assertTrue(query().findNearest().isEmpty());
    }

    /** The documented collapse; queryHintArrows is the way to see the reason. */
    @Test
    void refusedRead_looksLikeNoArrows() {
        when(api.queryHintArrows()).thenReturn(
                EntityRead.refused(EntityReadStatus.NOT_IN_WORLD, List.of()));

        assertTrue(query().all().isEmpty());
        assertNull(query().first());
        assertFalse(query().exists());
    }

    @Test
    void eachTerminalFetchesOnce() {
        query().ofType(HintArrowType.NPC).all();

        verify(api).queryHintArrows();
    }

    private HintArrows.Query query() {
        return HintArrows.query(api);
    }

    private static HintArrow arrow(HintArrowType type, int target, int plane, int x, int y) {
        return new HintArrow(HintArrow.Source.TREE, HintArrow.NO_VALUE, type, type.wireCode(), target, plane, x, y, 0);
    }

    private static LocalPlayer selfAt(int x, int y, int plane) {
        return new LocalPlayer(0, 100, x, y, plane, 0, -1, -1, 0, -1, 0, true, -1,
                LocalPlayer.HEALTH_UNKNOWN, LocalPlayer.HEALTH_UNKNOWN, List.of());
    }
}
