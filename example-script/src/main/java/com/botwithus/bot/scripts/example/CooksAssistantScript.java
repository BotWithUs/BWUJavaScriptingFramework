package com.botwithus.bot.scripts.example;

import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.quest.QuestScript;
import com.botwithus.bot.quest.QuestStep;
import com.botwithus.bot.quest.Quests;
import com.botwithus.bot.quest.Steps;
import com.botwithus.bot.quest.steps.SequenceStep;

import java.util.List;

/**
 * Reference {@link QuestScript} that solves Cook's Assistant (quest 257)
 * end-to-end. The quest tracker is varp 2492:
 * <ul>
 *   <li>{@code 0} — not started; talk to the Cook to accept.</li>
 *   <li>{@code 1} — gather a bucket of milk, a pot of flour, and an egg,
 *       then return to the Cook.</li>
 *   <li>{@code 2} — complete; the router terminates the script.</li>
 * </ul>
 *
 * <p>Item IDs are hard-coded since the cache-backed {@code getItemType}
 * RPC is still a stub. Once that lands the gather phases can resolve by
 * name through {@link com.botwithus.bot.api.inventory.Backpack#contains(String)}.</p>
 */
@ScriptManifest(
        name = "Cook's Assistant",
        version = "1.0",
        author = "BotWithUs",
        description = "Solves Cook's Assistant (quest 257) end-to-end.",
        category = ScriptCategory.QUESTING)
public final class CooksAssistantScript extends QuestScript {

    private static final int ITEM_BUCKET = 1925;
    private static final int ITEM_BUCKET_OF_MILK = 1927;
    private static final int ITEM_POT = 1931;
    private static final int ITEM_POT_OF_FLOUR = 1933;
    private static final int ITEM_EGG = 1944;
    private static final int ITEM_GRAIN = 1947;

    private static final int VARP_TRACKER = 2492;

    public CooksAssistantScript() {
        super(Quests.COOKS_ASSISTANT);
    }

    @Override
    protected List<QuestStep> defineSteps() {
        return List.of(
                startWithCook(),
                returnToCook(),
                gatherEgg(),
                gatherMilk(),
                gatherFlour()
        );
    }

    private SequenceStep startWithCook() {
        return Steps.sequence(
                Steps.walkTo(3209, 3215, 0),
                Steps.talkTo("Cook")
                        .selectOption("What's wrong?")
                        .selectOption("Yes")
                        .selectOption("I'll get right on it.")
        )
                .whenVar(VARP_TRACKER, 0)
                .named("Phase 0: accept quest");
    }

    private SequenceStep returnToCook() {
        // Highest priority so the router prefers handing in over gathering
        // any time all three ingredients are already on hand.
        return Steps.sequence(
                Steps.walkTo(3209, 3215, 0),
                Steps.talkTo("Cook"),
                Steps.waitForVar(VARP_TRACKER, 2)
        )
                .whenVar(VARP_TRACKER, 1)
                .andBackpackHas(ITEM_BUCKET_OF_MILK, ITEM_POT_OF_FLOUR, ITEM_EGG)
                .named("Phase 1c: return to Cook");
    }

    private SequenceStep gatherEgg() {
        return Steps.sequence(
                Steps.walkTo(3236, 3295, 0),
                Steps.pickUpGround(ITEM_EGG)
        )
                .whenVar(VARP_TRACKER, 1)
                .andBackpackMissing(ITEM_EGG)
                .named("Phase 1a: egg");
    }

    private SequenceStep gatherMilk() {
        return Steps.sequence(
                Steps.walkTo(3253, 3273, 0),
                Steps.pickUpGround(ITEM_BUCKET),
                Steps.useItemOnNpc(ITEM_BUCKET, "Dairy cow")
                        .produces(ITEM_BUCKET_OF_MILK)
        )
                .whenVar(VARP_TRACKER, 1)
                .andBackpackMissing(ITEM_BUCKET_OF_MILK)
                .named("Phase 1b: milk");
    }

    private SequenceStep gatherFlour() {
        return Steps.sequence(
                Steps.walkTo(3166, 3306, 0),
                Steps.pickUpGround(ITEM_POT),
                Steps.pickUpGround(ITEM_GRAIN),
                Steps.objectInteract("Staircase").withAction("Climb-up"),
                Steps.objectInteract("Staircase").withAction("Climb-up"),
                Steps.useItemOnObject(ITEM_GRAIN, "Hopper"),
                Steps.objectInteract("Hopper controls").withAction("Operate"),
                Steps.objectInteract("Staircase").withAction("Climb-down"),
                Steps.objectInteract("Staircase").withAction("Climb-down"),
                Steps.useItemOnObject(ITEM_POT, "Flour bin")
                        .produces(ITEM_POT_OF_FLOUR)
        )
                .whenVar(VARP_TRACKER, 1)
                .andBackpackMissing(ITEM_POT_OF_FLOUR)
                .named("Phase 1c: flour");
    }
}
