package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.GameAction;
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
 * The use-on recipe of NXTLibrary PUBLIC_PROTOCOL 6.3.1: arm with 25 (never 57) on the
 * item's slot, wait for target mode to hold the item, then one target action (58 / 8 / 2),
 * then wait for the agent to end target mode. The NPC and loc targets are in
 * {@code entities.UseOnTargetsTest}, where those entities can be built for real.
 */
class UseOnTest {

    /** {@code (1473 << 16) | 5}, the backpack grid as the protocol quotes it. */
    private static final int BACKPACK_HASH = 96534533;
    private static final int KNIFE = 946;
    private static final int LOGS = 1511;

    private final GameAPI api = mock(GameAPI.class);
    private final InventoryContainer backpack = mock(InventoryContainer.class);
    private final List<GameAction> queued = new ArrayList<>();
    private TargetMode mode = TargetMode.INACTIVE;
    /** What the scripted client does on an action: arm, end, or ignore it. */
    private boolean isArmable = true;
    private boolean isResetting = true;

    private final Waiter onePollPerAction = (condition, timeoutMs) -> condition.getAsBoolean();

    @BeforeEach
    void wire() {
        when(backpack.interfaceId()).thenReturn(1473);
        when(backpack.componentId()).thenReturn(5);
        when(backpack.getSlot(3)).thenReturn(new InventoryItem(3, KNIFE, 1));
        when(backpack.getSlot(7)).thenReturn(new InventoryItem(7, LOGS, 1));
        when(api.getTargetMode()).thenAnswer(i -> mode);
        doAnswer(i -> {
            GameAction a = i.getArgument(0);
            queued.add(a);
            if (a.actionId() == ActionTypes.SELECT_COMPONENT && isArmable) {
                mode = new TargetMode(true, true, KNIFE,
                        Optional.of(new TargetMode.Source(1473, 5, a.param2())), "Use", "Knife", 0);
            } else if (isResetting) {
                mode = TargetMode.INACTIVE;
            }
            return null;
        }).when(api).queueAction(any());
    }

    private UseOn useOn() {
        return new UseOn(api, backpack, onePollPerAction);
    }

    @Test
    void knifeOnLog_arms25ThenFires58_andSeesTargetModeEnd() {
        UseOn.Result r = useOn().onItem(new InventoryItem(3, KNIFE, 1), new InventoryItem(7, LOGS, 1));

        assertEquals(UseOn.Result.USED, r);
        assertEquals(List.of(new GameAction(25, 0, 3, BACKPACK_HASH), new GameAction(58, 0, 7, BACKPACK_HASH)),
                queued);
    }

    @Test
    void neverArmed_sendsNoTargetAction() {
        isArmable = false;

        UseOn.Result r = useOn().onItem(new InventoryItem(3, KNIFE, 1), new InventoryItem(7, LOGS, 1));

        assertEquals(UseOn.Result.NOT_ARMED, r);
        assertEquals(1, queued.size());
    }

    @Test
    void anAgentThatNeverResets_isUnconfirmed() {
        isResetting = false;

        UseOn.Result r = useOn().onItem(new InventoryItem(3, KNIFE, 1), new InventoryItem(7, LOGS, 1));

        assertEquals(UseOn.Result.QUEUED_UNCONFIRMED, r);
    }

    /**
     * Measured live by the native host's peer on 951-4: a client already armed from another
     * slot reads active, with the same item, before this arm lands. A predicate on
     * {@code active} (or on the item alone) would fire 58 on the old selection here.
     */
    @Test
    void alreadyArmedFromAnotherSlot_doesNotCountAsThisArm() {
        mode = new TargetMode(true, true, KNIFE, Optional.of(new TargetMode.Source(1473, 5, 9)),
                "Use", "Knife", 0);
        isArmable = false;
        isResetting = false;

        UseOn.Result r = useOn().onItem(new InventoryItem(3, KNIFE, 1), new InventoryItem(7, LOGS, 1));

        assertEquals(UseOn.Result.NOT_ARMED, r);
        assertEquals(List.of(new GameAction(25, 0, 3, BACKPACK_HASH)), queued, "no target action");
    }

    @Test
    void movedItems_queueNothing() {
        assertEquals(UseOn.Result.ITEM_MISSING,
                useOn().onItem(new InventoryItem(3, LOGS, 1), new InventoryItem(7, LOGS, 1)));
        assertEquals(UseOn.Result.TARGET_MISSING,
                useOn().onItem(new InventoryItem(3, KNIFE, 1), new InventoryItem(6, LOGS, 1)));
        assertEquals(List.of(), queued);
    }
}
