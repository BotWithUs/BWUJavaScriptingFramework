package com.botwithus.bot.quest.steps;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.entities.SceneObject;
import com.botwithus.bot.api.inventory.Backpack;
import com.botwithus.bot.api.snapshot.InventoryItem;
import com.botwithus.bot.quest.QuestContext;
import com.botwithus.bot.quest.QuestState;
import com.botwithus.bot.quest.QuestStep;
import com.botwithus.bot.quest.StepResult;

import java.time.Duration;
import java.util.function.Predicate;

/**
 * "Use {@code item} on {@code object}". Implemented as two queued clicks:
 * (1) "Use" the backpack slot carrying the item; (2) interact with the
 * scene object's default option. The game's click pipeline applies the
 * held item to the next clicked target.
 *
 * <p>Item resolution is by id when given (preferred — works against the
 * snapshot directly), else by name (relies on
 * {@link com.botwithus.bot.api.GameAPI#getItemType(int)}, which is a
 * slice-5 stub at the time of writing; expect this path to fall back to
 * raw {@code Use} option-index 2).</p>
 *
 * <p>Default success: {@link #productItemId} appears in the backpack OR
 * {@link #consumedItemId} count decreases — set with
 * {@link #produces(int)} / {@link #consumes(int)} to make the predicate
 * sharp. Without either, success defaults to "true" after one execute.</p>
 */
public final class UseItemOnObjectStep implements QuestStep {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    private final int itemId;
    private final String itemName;
    private final String objectName;
    private int productItemId = -1;
    private int consumedItemId = -1;
    private int baselineConsumedCount;
    private Duration timeout = DEFAULT_TIMEOUT;

    public UseItemOnObjectStep(int itemId, String itemName, String objectName) {
        this.itemId = itemId;
        this.itemName = itemName;
        this.objectName = objectName;
    }

    public UseItemOnObjectStep produces(int productItemId) {
        this.productItemId = productItemId;
        return this;
    }

    public UseItemOnObjectStep consumes(int consumedItemId) {
        this.consumedItemId = consumedItemId;
        return this;
    }

    public UseItemOnObjectStep withTimeout(Duration t) {
        this.timeout = t;
        return this;
    }

    @Override
    public String name() {
        return "useItemOnObject(" + identifier() + " → " + objectName + ")";
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
        SceneObject target = api.objects().nearest(objectName);
        if (target == null) {
            return StepResult.retry("object '" + objectName + "' not in scene");
        }
        if (!bag.interactFirst(slot.itemId(), "Use") && !bag.use(slot.itemId())) {
            return StepResult.retry("failed to Use item " + identifier());
        }
        try {
            target.interact(1);
        } catch (RuntimeException e) {
            return StepResult.retry("failed default interact on '" + objectName + "': " + e.getMessage());
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
