package com.botwithus.bot.api.world;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.ComponentOp;
import com.botwithus.bot.api.model.ComponentOps;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LodestoneNetwork} with fallback ids. Teleports are driven against a snapshot whose
 * self tile jumps when the destination click is queued, so a teleport that clicked the wrong
 * button, or judged arrival before moving, fails.
 */
class LodestoneNetworkTest {

    private static final int NETWORK = 1092;
    private static final int HOME_BUTTON = (1465 << 16) | 34;
    private static final int LUMBRIDGE_BUTTON = (NETWORK << 16) | 17;

    private final GameAPI api = mock(GameAPI.class);
    private final GameSnapshot snapshot = mock(GameSnapshot.class);
    private final AtomicReference<LocalPlayer> self = new AtomicReference<>(at(3222, 3218));
    private final LodestoneNetwork network = new LodestoneNetwork(api);

    @BeforeEach
    void setUp() {
        when(api.gamevals()).thenReturn(GamevalIndex.empty());
        when(api.snapshot()).thenReturn(snapshot);
        when(snapshot.gameState()).thenReturn(GameSnapshot.GAME_STATE_IN_GAME);
        when(snapshot.self()).thenAnswer(inv -> self.get());
    }

    @Test
    void unlock_thresholds_followEachLodestonesVarbit() {
        when(api.getVarbit(35)).thenReturn(1);
        when(api.getVarbit(10236)).thenReturn(99);
        assertTrue(network.isUnlocked(Lodestone.LUMBRIDGE));
        assertFalse(network.isUnlocked(Lodestone.LUNAR_ISLE));
        when(api.getVarbit(10236)).thenReturn(100);
        assertTrue(network.isUnlocked(Lodestone.LUNAR_ISLE));
    }

    @Test
    void teleport_locked_clicksNothing() {
        when(api.getVarbit(35)).thenReturn(0);

        assertEquals(LodestoneNetwork.Result.LOCKED, network.teleport(Lodestone.LUMBRIDGE, 100));
        verify(api, never()).queueAction(any());
    }

    @Test
    void teleport_withNetworkOpen_clicksTheDestinationAndWaitsForTheJump() {
        when(api.getVarbit(35)).thenReturn(1);
        when(api.isInterfaceOpen(NETWORK)).thenReturn(true);
        GameAction click = new GameAction(ActionTypes.COMPONENT, 1, -1, LUMBRIDGE_BUTTON);
        doAnswer(inv -> {
            self.set(at(3233, 3221 - 1_000));
            return null;
        }).when(api).queueAction(click);

        assertEquals(LodestoneNetwork.Result.ARRIVED, network.teleport(Lodestone.LUMBRIDGE, 2_000));
        verify(api).queueAction(click);
    }

    @Test
    void teleport_withNoMovement_timesOut() {
        when(api.getVarbit(35)).thenReturn(1);
        when(api.isInterfaceOpen(NETWORK)).thenReturn(true);

        assertEquals(LodestoneNetwork.Result.TIMED_OUT, network.teleport(Lodestone.LUMBRIDGE, 300));
    }

    @Test
    void teleportToPrevious_usesTheNamedOption() {
        when(api.getComponentOps(1465, 34, -1)).thenReturn(new ComponentOps(0, List.of(
                new ComponentOp(1, "Home Teleport", true),
                new ComponentOp(3, "Previous Destination", true))));
        doAnswer(inv -> {
            self.set(at(2899, 3544));
            return null;
        }).when(api).queueAction(any());

        assertEquals(LodestoneNetwork.Result.ARRIVED, network.teleportToPrevious(2_000));
        verify(api).queueAction(new GameAction(ActionTypes.COMPONENT, 3, -1, HOME_BUTTON));
    }

    @Test
    void arrival_needsTheFullDistance() {
        assertFalse(LodestoneNetwork.hasArrived(at(3200, 3200), at(3210, 3205)));
        assertTrue(LodestoneNetwork.hasArrived(at(3200, 3200), at(3200, 3220)));
    }

    private static LocalPlayer at(int x, int y) {
        return new LocalPlayer(7, 90, x, y, 0, 0, -1, -1, -1, -1, 0, true, -1, 990, 990, List.of());
    }
}
