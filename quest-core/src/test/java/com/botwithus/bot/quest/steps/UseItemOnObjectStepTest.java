package com.botwithus.bot.quest.steps;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.entities.SceneObject;
import com.botwithus.bot.api.entities.SceneObjects;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.inventory.Backpack;
import com.botwithus.bot.api.inventory.InventoryContainer;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.ItemType;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.Inventory;
import com.botwithus.bot.api.snapshot.InventoryItem;
import com.botwithus.bot.api.util.Interfaces;
import com.botwithus.bot.quest.QuestContext;
import com.botwithus.bot.quest.QuestId;
import com.botwithus.bot.quest.QuestState;
import com.botwithus.bot.quest.StepResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The use-on step still selects its item through "Use", which the live ops never list.
 * Before the inventory interact read live ops, "Use" resolved to its position in the
 * item's cache options; it still does, so this step behaves as it did.
 */
class UseItemOnObjectStepTest {

    private static final int BUCKET = 1925;
    private static final int SLOT = 4;
    private static final int SLOT_COUNT = 28;
    /** "Use" is second in the bucket's cache options below, so the lookup sends op 2. */
    private static final int USE_CACHE_POSITION = 2;
    private static final String WELL = "Well";
    private static final long NO_DEADLINE = Long.MAX_VALUE;

    private final GameAPI api = mock(GameAPI.class);
    private final SceneObject well = mock(SceneObject.class);
    private QuestContext ctx;

    @BeforeEach
    void wireABackpackHoldingABucket() {
        GameSnapshot snapshot = mock(GameSnapshot.class);
        GameSnapshot.Inventories inventories = mock(GameSnapshot.Inventories.class);
        Inventory backpack = new Inventory(Backpack.INVENTORY_ID, SLOT_COUNT,
                List.of(new InventoryItem(SLOT, BUCKET, 1)));
        when(inventories.byInvId(Backpack.INVENTORY_ID)).thenReturn(Optional.of(backpack));
        when(snapshot.inventories()).thenReturn(inventories);
        when(api.snapshot()).thenReturn(snapshot);
        when(api.backpack()).thenReturn(new Backpack(api));
        when(api.getItemType(BUCKET)).thenReturn(new ItemType(BUCKET, "Bucket", false, false, 0, 0,
                0, -1, -1, false, List.of(), List.of("Fill", InventoryContainer.USE, "Empty"),
                Map.of()));
        SceneObjects objects = mock(SceneObjects.class);
        when(objects.nearest(WELL)).thenReturn(well);
        when(api.objects()).thenReturn(objects);
        ScriptContext script = mock(ScriptContext.class);
        when(script.getGameAPI()).thenReturn(api);
        ctx = new QuestContext(script, new QuestId(0, "test", List.of()), new QuestState(Map.of()),
                NO_DEADLINE, 0);
    }

    @Test
    void execute_selectsTheItemByItsCachePositionAndReadsNoOps() {
        StepResult result = new UseItemOnObjectStep(BUCKET, "Bucket", WELL).execute(ctx);

        assertEquals(StepResult.done(), result);
        verify(api).queueAction(new GameAction(ActionTypes.COMPONENT, USE_CACHE_POSITION, SLOT,
                Interfaces.componentHash(Backpack.INTERFACE_ID, Backpack.COMPONENT_ID)));
        verify(api, never()).getComponentOps(anyInt(), anyInt(), anyInt());
    }
}
