package com.botwithus.bot.quest.steps;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.entities.Npc;
import com.botwithus.bot.api.inventory.Backpack;
import com.botwithus.bot.api.snapshot.InventoryItem;
import com.botwithus.bot.quest.QuestContext;
import com.botwithus.bot.quest.QuestState;
import com.botwithus.bot.quest.QuestStep;
import com.botwithus.bot.quest.StepResult;

import java.time.Duration;
import java.util.function.Predicate;

/**
 * "Use {@code item} on {@code npc}". Mirror of
 * {@link UseItemOnObjectStep} for NPCs. Two queued clicks: "Use" the
 * backpack slot, then default-interact the NPC.
 */
public final class UseItemOnNpcStep implements QuestStep {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    private final int itemId;
    private final String itemName;
    private final String npcName;
    private int productItemId = -1;
    private int consumedItemId = -1;
    private int baselineConsumedCount;
    private Duration timeout = DEFAULT_TIMEOUT;

    public UseItemOnNpcStep(int itemId, String itemName, String npcName) {
        this.itemId = itemId;
        this.itemName = itemName;
        this.npcName = npcName;
    }

    public UseItemOnNpcStep produces(int productItemId) {
        this.productItemId = productItemId;
        return this;
    }

    public UseItemOnNpcStep consumes(int consumedItemId) {
        this.consumedItemId = consumedItemId;
        return this;
    }

    public UseItemOnNpcStep withTimeout(Duration t) {
        this.timeout = t;
        return this;
    }

    @Override
    public String name() {
        return "useItemOnNpc(" + identifier() + " → " + npcName + ")";
    }

    @Override
    public boolean appliesTo(QuestState state, GameAPI api) {
        return true;
    }

    @Override
    public void onEnter(QuestContext ctx) {
        if (consumedItemId >= 0) {
            baselineConsumedCount = ctx.api().backpack().count(consumedItemId);
        }
    }

    @Override
    public StepResult execute(QuestContext ctx) {
        GameAPI api = ctx.api();
        Backpack bag = api.backpack();
        InventoryItem slot = resolveSlot(bag);
        if (slot == null) {
            return StepResult.retry("item '" + identifier() + "' not in backpack");
        }
        Npc target = api.npcs().nearest(npcName);
        if (target == null) {
            return StepResult.retry("NPC '" + npcName + "' not in scene");
        }
        if (!bag.interactFirst(slot.itemId(), "Use") && !bag.use(slot.itemId())) {
            return StepResult.retry("failed to Use item " + identifier());
        }
        try {
            target.interact(1);
        } catch (RuntimeException e) {
            return StepResult.retry("failed default interact on NPC '" + npcName + "': " + e.getMessage());
        }
        return StepResult.done();
    }

    @Override
    public Predicate<QuestContext> success() {
        return ctx -> {
            Backpack bag = ctx.api().backpack();
            if (productItemId >= 0 && bag.contains(productItemId)) {
                return true;
            }
            if (consumedItemId >= 0 && bag.count(consumedItemId) < baselineConsumedCount) {
                return true;
            }
            return productItemId < 0 && consumedItemId < 0;
        };
    }

    @Override
    public Duration timeout() {
        return timeout;
    }

    private InventoryItem resolveSlot(Backpack bag) {
        if (itemId >= 0) {
            return bag.getFirst(itemId);
        }
        return itemName == null ? null : bag.getFirst(itemName);
    }

    private String identifier() {
        return itemId >= 0 ? Integer.toString(itemId) : itemName;
    }
}
