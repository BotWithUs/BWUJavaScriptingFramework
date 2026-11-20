package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.inventory.InventoryContainer;
import com.botwithus.bot.api.inventory.UseOn;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.SceneObjectInfo;
import com.botwithus.bot.api.model.TargetMode;
import com.botwithus.bot.api.snapshot.InventoryItem;
import com.botwithus.bot.api.util.Waiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link UseOn}'s NPC and loc target actions (PUBLIC_PROTOCOL 6.3.1): {@code SELECT_NPC}
 * (8) with the server index, {@code SELECT_OBJECT} (2) with the loc type id and its own
 * tile. Real {@link Npc} and {@link SceneObject} wrappers, built in their own package.
 */
class UseOnTargetsTest {

    private static final int KNIFE = 946;

    private final GameAPI api = mock(GameAPI.class);
    private final InventoryContainer backpack = mock(InventoryContainer.class);
    private final List<GameAction> queued = new ArrayList<>();
    private TargetMode mode = TargetMode.INACTIVE;
    private final Waiter immediate = (condition, timeoutMs) -> condition.getAsBoolean();

    @BeforeEach
    void wire() {
        when(backpack.interfaceId()).thenReturn(1473);
        when(backpack.componentId()).thenReturn(5);
        when(backpack.getSlot(3)).thenReturn(new InventoryItem(3, KNIFE, 1));
        when(api.getTargetMode()).thenAnswer(i -> mode);
        doAnswer(i -> {
            GameAction a = i.getArgument(0);
            queued.add(a);
            mode = a.actionId() == ActionTypes.SELECT_COMPONENT
                    ? new TargetMode(true, true, KNIFE,
                            Optional.of(new TargetMode.Source(1473, 5, a.param2())), "Use", "Knife", 0)
                    : TargetMode.INACTIVE;
            return null;
        }).when(api).queueAction(any());
    }

    @Test
    void onNpc_firesSelectNpcWithTheServerIndex() {
        Npc npc = new Npc(api, SceneFixture.npc(4321, 3, 3200, 3201, 0, -1, 10, 10), id -> null);

        UseOn.Result r = new UseOn(api, backpack, immediate).onNpc(new InventoryItem(3, KNIFE, 1), npc);

        assertEquals(UseOn.Result.USED, r);
        assertEquals(new GameAction(8, 4321, 0, 0), queued.get(1));
    }

    @Test
    void onObject_firesSelectObjectWithTypeIdAndItsTile() {
        SceneObject tree = new SceneObject(api, new SceneObjectInfo(0, 1276, 1276, 3205, 3243, 0, "Tree",
                List.of("Chop down"), SceneObjectInfo.UNKNOWN_SHAPE, SceneObjectInfo.UNKNOWN_ROTATION,
                SceneObjectInfo.NO_ANIMATION, false), id -> null);

        new UseOn(api, backpack, immediate).onObject(new InventoryItem(3, KNIFE, 1), tree);

        assertEquals(new GameAction(2, 1276, 3205, 3243), queued.get(1));
    }
}
