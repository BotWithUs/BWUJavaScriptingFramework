package com.botwithus.bot.quest.steps;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.inventory.Backpack;
import com.botwithus.bot.quest.QuestContext;
import com.botwithus.bot.quest.QuestState;
import com.botwithus.bot.quest.QuestStep;
import com.botwithus.bot.quest.StepResult;

import java.time.Duration;
import java.util.function.Predicate;

/**
 * Equip (wear / wield) a backpack item. Success once the item appears in the
 * worn-equipment container.
 *
 * <p>Option resolution: the named-option route ({@code interactFirst(id,
 * "Wear")}) depends on {@link GameAPI#getItemType(int)}, which is a slice-5
 * stub at the time of writing, so it returns {@code false} for everything.
 * The reliable route is the <em>numeric</em> option index
 * ({@link com.botwithus.bot.api.inventory.InventoryContainer#interactFirst(int, int)}),
 * which clicks the slot's option by position regardless of label. Pass it with
 * {@link #withOption(int)} (e.g. an amulet's "Wear" is option index 2 because
 * inventory option 1 is blank). With no explicit index the step tries the
 * named options "Wear" / "Wield" / "Equip" in turn — correct once the
 * getItemType cache lands.</p>
 *
 * <p>Id-based use is preferred. The name-based path relies on the same stubbed
 * lookup for both the click and the success check, so it is best-effort only.</p>
 */
public final class EquipItemStep implements QuestStep {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    private static final String[] WEAR_OPTIONS = {"Wear", "Wield", "Equip"};

    private final int itemId;
    private final String itemName;
    private int optionIndex = -1;
    private Duration timeout = DEFAULT_TIMEOUT;

    public EquipItemStep(int itemId, String itemName) {
        this.itemId = itemId;
        this.itemName = itemName;
    }

    /** Pin the 1-based inventory option index to click (bypasses getItemType). */
    public EquipItemStep withOption(int oneBasedIndex) {
        this.optionIndex = oneBasedIndex;
        return this;
    }

    public EquipItemStep withTimeout(Duration t) {
        this.timeout = t;
        return this;
    }

    @Override
    public String name() {
        return "equip(" + identifier() + ")";
    }

    @Override
    public boolean appliesTo(QuestState state, GameAPI api) {
        return true;
    }

    @Override
    public StepResult execute(QuestContext ctx) {
        GameAPI api = ctx.api();
        if (isEquipped(api)) {
            return StepResult.done();
        }
        Backpack bag = api.backpack();
        if (itemId >= 0 && !bag.contains(itemId)) {
            return StepResult.retry("item " + identifier() + " not in backpack");
        }
        if (queueWear(bag)) {
            return StepResult.done();
        }
        return StepResult.retry("no wear option for " + identifier());
    }

    @Override
    public Predicate<QuestContext> success() {
        return ctx -> isEquipped(ctx.api());
    }

    @Override
    public Duration timeout() {
        return timeout;
    }

    private boolean queueWear(Backpack bag) {
        if (itemId >= 0) {
            if (optionIndex > 0) {
                return bag.interactFirst(itemId, optionIndex);
            }
            for (String opt : WEAR_OPTIONS) {
                if (bag.interactFirst(itemId, opt)) {
                    return true;
                }
            }
            return false;
        }
        if (itemName == null) {
            return false;
        }
        var slot = bag.getFirst(itemName);
        if (slot == null) {
            return false;
        }
        if (optionIndex > 0) {
            return bag.interact(slot.slot(), optionIndex);
        }
        for (String opt : WEAR_OPTIONS) {
            if (bag.interactFirst(slot.itemId(), opt)) {
                return true;
            }
        }
        return false;
    }

    private boolean isEquipped(GameAPI api) {
        if (itemId >= 0) {
            return api.equipment().contains(itemId);
        }
        return itemName != null && api.equipment().contains(itemName);
    }

    private String identifier() {
        return itemId >= 0 ? Integer.toString(itemId) : itemName;
    }
}
