package com.botwithus.bot.api.world;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.ScreenProjection;
import com.botwithus.bot.api.model.WorldToScreen;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.snapshot.Orientation;
import com.botwithus.bot.api.util.Waiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link Worlds#hop}: the agent's {@code started} is the predicate, {@code reason} the
 * message, and arrival is the world number plus (from the world) the transfer finishing.
 * The waiter here advances a scripted client one "poll" at a time instead of sleeping.
 */
class WorldsTest {

    private static final int TARGET = 84;
    private static final int HOME = 2;
    private static final int TRANSFER = 37;
    private static final int POLLS = 20;

    private final GameAPI api = mock(GameAPI.class);
    private final GameSnapshot snapshot = mock(GameSnapshot.class);
    /** Each poll pops the next (world, gameState); the last one sticks. */
    private final Deque<int[]> script = new ArrayDeque<>();
    private int world = HOME;
    private int gameState = GameSnapshot.GAME_STATE_IN_GAME;

    private final Waiter steppingWaiter = (condition, timeoutMs) -> {
        for (int i = 0; i < POLLS; i++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            advance();
        }
        return false;
    };

    @BeforeEach
    void wire() {
        when(api.snapshot()).thenReturn(snapshot);
        when(snapshot.gameState()).thenAnswer(i -> gameState);
        when(api.getCurrentWorld()).thenAnswer(i -> world);
    }

    private void advance() {
        int[] next = script.size() > 1 ? script.poll() : script.peek();
        if (next != null) {
            world = next[0];
            gameState = next[1];
        }
    }

    private Worlds worlds() {
        return new Worlds(api, steppingWaiter);
    }

    @Test
    void refused_returnsTheReason_andWaitsForNothing() {
        when(api.requestWorld(TARGET)).thenReturn(new WorldHopRequest(false, "members_world"));

        Worlds.HopResult r = worlds().hop(TARGET);

        assertEquals(Worlds.HopOutcome.REFUSED, r.outcome());
        assertEquals("members_world", r.reason());
        assertEquals(Optional.of(HopRefusal.MEMBERS_WORLD), r.refusal());
        verify(api, never()).getCurrentWorld();
    }

    @Test
    void inWorld_arrivesOnlyAfterTheTransferIsBackInGame() {
        when(api.requestWorld(TARGET)).thenReturn(new WorldHopRequest(true, ""));
        script.add(new int[] {HOME, 30});
        // The world number changes while the client still reads 30, then transfers.
        script.add(new int[] {TARGET, 30});
        script.add(new int[] {TARGET, TRANSFER});
        script.add(new int[] {TARGET, TRANSFER});
        script.add(new int[] {TARGET, 30});

        Worlds.HopResult r = worlds().hop(TARGET);

        assertTrue(r.isArrived());
        assertEquals(GameSnapshot.GAME_STATE_IN_GAME, gameState);
        assertTrue(script.size() <= 1, "the hop must have waited through the transfer");
    }

    @Test
    void inWorld_neverBackInGame_timesOut() {
        when(api.requestWorld(TARGET)).thenReturn(new WorldHopRequest(true, ""));
        script.add(new int[] {TARGET, TRANSFER});

        Worlds.HopResult r = worlds().hop(TARGET);

        assertEquals(Worlds.HopOutcome.TIMED_OUT, r.outcome());
    }

    @Test
    void fromLobby_theWorldNumberIsArrival() {
        gameState = Worlds.GAME_STATE_LOBBY;
        when(api.requestWorld(TARGET)).thenReturn(new WorldHopRequest(true, ""));
        script.add(new int[] {TARGET, Worlds.GAME_STATE_LOBBY});

        assertTrue(worlds().hop(TARGET).isArrived());
    }

    /** Back at state 30 a moment before the scene loads: projection answers unavailable. */
    @Test
    void sceneReady_waitsPastUnavailable() {
        LocalPlayer self = new LocalPlayer(0, 0, 3222, 3218, 0, 0, -1, -1, -1, -1, -1, false, -1,
                LocalPlayer.HEALTH_UNKNOWN, LocalPlayer.HEALTH_UNKNOWN, List.of(),
                Orientation.unknown(), LocalPlayer.RUN_UNKNOWN, LocalPlayer.RUN_UNKNOWN);
        when(snapshot.self()).thenReturn(self);
        WorldToScreen unavailable = new WorldToScreen(Optional.empty(), List.of(
                new ScreenProjection(ScreenProjection.Result.UNAVAILABLE, false,
                        ScreenProjection.NO_PIXEL, ScreenProjection.NO_PIXEL)));
        WorldToScreen ok = new WorldToScreen(Optional.of(new WorldToScreen.Viewport(0, 0, 800, 600)),
                List.of(new ScreenProjection(ScreenProjection.Result.OK, true, 400, 300)));
        when(api.worldToScreen(anyList())).thenReturn(unavailable, unavailable, ok);

        assertTrue(worlds().awaitSceneReady(1_000));
        verify(api, times(3)).worldToScreen(anyList());
    }

    @Test
    void sceneReady_neverWithNoLocalPlayer() {
        assertFalse(worlds().awaitSceneReady(1_000));
        verify(api, never()).worldToScreen(anyList());
    }

    @Test
    void worldNeverChanges_timesOut() {
        when(api.requestWorld(anyInt())).thenReturn(new WorldHopRequest(true, ""));

        Worlds.HopResult r = worlds().hop(TARGET);

        assertFalse(r.isArrived());
        assertEquals(Optional.empty(), r.refusal());
    }
}
