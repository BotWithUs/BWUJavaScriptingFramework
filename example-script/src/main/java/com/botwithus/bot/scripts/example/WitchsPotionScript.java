package com.botwithus.bot.scripts.example;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.quest.QuestScript;
import com.botwithus.bot.quest.QuestState;
import com.botwithus.bot.quest.QuestStep;
import com.botwithus.bot.quest.Quests;
import com.botwithus.bot.quest.Steps;

import java.util.List;
import java.util.function.BiPredicate;

/**
 * Reference {@link QuestScript} for Witch's Potion (miniquest 178, varp 2473).
 * Second hand-authored conversion (after {@link RestlessGhostScript}) proving
 * the bolt&rarr;Steps recipe generalises.
 *
 * <h2>Authoring</h2>
 * Hand-written from the GPL-3.0 {@code bolt-questhelper} as a factual reference
 * only (tiles, dialogue strings, step order, names). Item ids resolved from the
 * NXT cache by name ({@code nxtcache-dumper --type item}):
 * Rat's tail {@value #RATS_TAIL}, Eye of newt {@value #EYE_OF_NEWT},
 * Onion {@value #ONION}, Burnt meat {@value #BURNT_MEAT}.
 *
 * <h2>Required consumables</h2>
 * The player must bring an <b>eye of newt</b>, an <b>onion</b>, and a piece of
 * <b>burnt meat</b> (shop/cook these beforehand — out of quest scope, same as
 * bolt lists them as needed items). The rat's tail is obtained in-quest.
 *
 * <h2>Progression model</h2>
 * varp 2473 is a coarse anchor: {@code 0} not started, {@code 1..2} in progress,
 * {@code 3} complete. Ordering is driven by inventory (rat's tail present?), not
 * exact varp values. Sequences do <em>not</em> trail a {@code waitForVar} —
 * {@link com.botwithus.bot.quest.steps.SequenceStep} polls child success against
 * the start-of-sequence state snapshot (never refreshed), so an in-sequence
 * {@code waitForVar} stalls; the router re-picks on fresh state instead.
 *
 * <h2>Needs in-game verification</h2>
 * Hetty's tile / rat spawn tile, the "Cauldron" object name + "Drink" option,
 * and the completion varp value (assumed 3 = range high).
 */
@ScriptManifest(
        name = "Witch's Potion",
        version = "0.1",
        author = "BotWithUs",
        description = "Solves Witch's Potion (miniquest 178) end-to-end.",
        category = ScriptCategory.QUESTING)
public final class WitchsPotionScript extends QuestScript {

    private static final int VARP_TRACKER = 2473;

    private static final int RATS_TAIL = 300;
    private static final int EYE_OF_NEWT = 221;
    private static final int ONION = 1957;
    private static final int BURNT_MEAT = 2146;

    public WitchsPotionScript() {
        super(Quests.WITCHS_POTION_MINIQUEST);
    }

    @Override
    protected List<QuestStep> defineSteps() {
        return List.of(
                acceptFromHetty(),
                getRatsTail(),
                brewAndDrink());
    }

    /** Phase 0 — talk to Hetty in Rimmington and accept the miniquest. */
    private QuestStep acceptFromHetty() {
        return Steps.sequence(
                Steps.walkTo(2967, 3206, 0),
                Steps.talkTo("Hetty").selectOption("I'm looking for work."),
                Steps.acceptQuest()
        )
                .whenVar(VARP_TRACKER, 0)
                .named("Phase 0: accept from Hetty");
    }

    /** Phase 1 — kill a Rimmington rat and pick up its tail. */
    private QuestStep getRatsTail() {
        return Steps.sequence(
                Steps.walkTo(2957, 3192, 0),
                Steps.killNpc("Rat"),
                Steps.pickUpGround(RATS_TAIL)
        )
                .whenVarInRange(VARP_TRACKER, 1, 2)
                .andBackpackMissing(RATS_TAIL)
                .named("Phase 1: rat's tail from a Rimmington rat");
    }

    /**
     * Phase 2 — hand Hetty the four ingredients (the talk auto-gives them) and
     * drink from the cauldron to finish. Gated on having the rat's tail plus
     * the three brought consumables.
     */
    private QuestStep brewAndDrink() {
        return Steps.sequence(
                Steps.walkTo(2967, 3206, 0),
                Steps.talkTo("Hetty"),
                Steps.objectInteract("Cauldron").withAction("Drink")
        )
                .whenVarInRange(VARP_TRACKER, 1, 2)
                .andCondition(hasAllIngredients())
                .named("Phase 2: give ingredients, drink cauldron");
    }

    /** True once the rat's tail and the three brought consumables are all held. */
    private static BiPredicate<QuestState, GameAPI> hasAllIngredients() {
        return (state, api) -> api.backpack().containsAll(
                RATS_TAIL, EYE_OF_NEWT, ONION, BURNT_MEAT);
    }
}
