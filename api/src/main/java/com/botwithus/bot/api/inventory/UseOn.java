package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.entities.Npc;
import com.botwithus.bot.api.entities.SceneObject;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.snapshot.InventoryItem;
import com.botwithus.bot.api.util.Interfaces;
import com.botwithus.bot.api.util.Waiter;

/**
 * "Use X on Y" from an inventory grid: the two clicks a player makes, as two queued actions.
 *
 * <ol>
 *   <li>arm: {@link ActionTypes#SELECT_COMPONENT} (25) on the item's slot. Only 25 arms
 *       target mode; {@link ActionTypes#COMPONENT} (57) would just click the item.</li>
 *   <li>wait until {@code get_target_mode} reads the item selected <b>from that slot</b>
 *       (item and source component and sub all match), so the target action is never sent
 *       on nothing, nor on an earlier selection: a client already armed from another slot
 *       reads active before this arm lands, measured live on 951-4;</li>
 *   <li>the target action: {@link ActionTypes#SELECT_COMPONENT_ITEM} (58) on an item slot,
 *       {@link ActionTypes#SELECT_NPC} (8) on an NPC, {@link ActionTypes#SELECT_OBJECT} (2)
 *       on a loc;</li>
 *   <li>wait until target mode has ended. The agent ends it after a target action, as the
 *       client does after a click, so that is how the host knows the action ran.</li>
 * </ol>
 *
 * <p>Ground items, players and tiles have target actions too ({@code SELECT_GROUND_ITEM} 17,
 * {@code SELECT_PLAYER} 15, {@code COMP_ON_PLAYER} 16, {@code SELECT_TILE} 59), but the
 * agent's protocol does not yet document how they are packed, so there is no helper for
 * them here.</p>
 *
 * <p>Blocks the calling thread for up to two waits of {@link #ARM_TIMEOUT_MS} each: the
 * action queue sends one action per server tick.</p>
 */
public final class UseOn {

    /** Longest wait for target mode to arm, and then to end, in ms (about five ticks). */
    public static final long ARM_TIMEOUT_MS = 3_000L;
    /** {@code param1} of the arm and item-target actions: the agent ignores it. */
    private static final int IGNORED_PARAM = 0;
    /** {@code param2} / {@code param3} of an NPC target action. */
    private static final int UNUSED_PARAM = 0;

    /** How a use-on attempt ended. */
    public enum Result {
        /** The target action ran: target mode armed with the item, then ended. */
        USED,
        /**
         * The target action was queued but target mode had not ended when time ran out. An
         * agent that predates the target-mode reset always ends here, and leaves the client
         * armed.
         */
        QUEUED_UNCONFIRMED,
        /** Target mode never armed with the item; no target action was queued. */
        NOT_ARMED,
        /** The item is no longer in its slot; nothing was queued. */
        ITEM_MISSING,
        /** The target item is no longer in its slot; nothing was queued. */
        TARGET_MISSING
    }

    private final GameAPI api;
    private final InventoryContainer container;
    private final Waiter waiter;

    /**
     * @param api       the queue and the target-mode read
     * @param container the grid both items are in
     * @param waiter    does both waits
     */
    public UseOn(GameAPI api, InventoryContainer container, Waiter waiter) {
        this.api = api;
        this.container = container;
        this.waiter = waiter;
    }

    /** Uses {@code item} on {@code target}, another item in the same grid. */
    public Result onItem(InventoryItem item, InventoryItem target) {
        if (!isStillInSlot(target)) {
            return Result.TARGET_MISSING;
        }
        return use(item, new GameAction(ActionTypes.SELECT_COMPONENT_ITEM, IGNORED_PARAM,
                target.slot(), gridHash()));
    }

    /** Uses {@code item} on {@code npc}. */
    public Result onNpc(InventoryItem item, Npc npc) {
        return use(item, new GameAction(ActionTypes.SELECT_NPC, npc.serverIndex(),
                UNUSED_PARAM, UNUSED_PARAM));
    }

    /** Uses {@code item} on the loc {@code object}, anchored on its own tile. */
    public Result onObject(InventoryItem item, SceneObject object) {
        return use(item, new GameAction(ActionTypes.SELECT_OBJECT, object.typeId(),
                object.tileX(), object.tileY()));
    }

    private Result use(InventoryItem item, GameAction target) {
        if (!isStillInSlot(item)) {
            return Result.ITEM_MISSING;
        }
        api.queueAction(new GameAction(ActionTypes.SELECT_COMPONENT, IGNORED_PARAM,
                item.slot(), gridHash()));
        if (!waiter.until(() -> api.getTargetMode().isSelectedFrom(item.itemId(),
                container.interfaceId(), container.componentId(), item.slot()), ARM_TIMEOUT_MS)) {
            return Result.NOT_ARMED;
        }
        api.queueAction(target);
        boolean isEnded = waiter.until(() -> !api.getTargetMode().active(), ARM_TIMEOUT_MS);
        return isEnded ? Result.USED : Result.QUEUED_UNCONFIRMED;
    }

    private boolean isStillInSlot(InventoryItem item) {
        if (item == null) {
            return false;
        }
        InventoryItem now = container.getSlot(item.slot());
        return now != null && now.itemId() == item.itemId();
    }

    private int gridHash() {
        return Interfaces.componentHash(container.interfaceId(), container.componentId());
    }
}
