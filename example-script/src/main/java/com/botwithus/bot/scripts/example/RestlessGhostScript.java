package com.botwithus.bot.scripts.example;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.quest.QuestScript;
import com.botwithus.bot.quest.QuestState;
import com.botwithus.bot.quest.QuestStep;
import com.botwithus.bot.quest.Quests;
import com.botwithus.bot.quest.Steps;

import java.time.Duration;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * Reference {@link QuestScript} that solves The Restless Ghost (quest 27)
 * end-to-end. Tracker var is varp 2324.
 *
 * <h2>How this was authored</h2>
 * Hand-written using the GPL-3.0 {@code bolt-questhelper} as a <em>factual
 * reference only</em> (tile coordinates, dialogue-option strings, step
 * ordering, NPC/object names). No bolt code or prose was copied. Entity
 * <em>ids</em> are <strong>not</strong> available from bolt (its {@code
 * Model.new(n, ...)} first arg is a mesh vertex count for render-scraping,
 * not an id); the item ids below were resolved from the NXT cache by name via
 * {@code nxtcache-dumper --type item}:
 * <ul>
 *   <li>Ghostspeak amulet (F2P) = {@value #AMULET_GHOSTSPEAK}; inventory
 *       "Wear" is option index {@value #AMULET_WEAR_OPTION} (slot option 1 is
 *       blank). The members variant 4250 is intentionally not used.</li>
 *   <li>Muddy skull = {@value #MUDDY_SKULL}.</li>
 * </ul>
 *
 * <h2>Progression model</h2>
 * The varp is a <em>coarse anchor</em> only: {@code 0} = not started, {@code
 * 1..4} = in progress, {@code 5} = complete. Intermediate ordering is driven
 * by live state (inventory / equipment), not by exact varp values — so no
 * value&rarr;phase calibration is needed. Note the equip subtlety: a worn
 * amulet leaves the backpack, so the "needs amulet" gate checks
 * <em>both</em> backpack and equipment.
 *
 * <h2>Needs in-game verification</h2>
 * <ul>
 *   <li>Quest-accept UI: handled by {@code Steps.acceptQuest()} (chat-path +
 *       label scan), confirmed via {@code waitForVar(2324, >=1)}. If Father
 *       Aereck uses a quest-offer scroll, pin its interface id with
 *       {@code Steps.acceptQuest().viaInterface(id)} once known.</li>
 *   <li>Father Urhney's hut tile (3147, 3176) — bolt only gives a waypoint.</li>
 *   <li>"Rocks" object name + "Search" option, and the completion varp value
 *       (assumed 5 = range high).</li>
 * </ul>
 */
@ScriptManifest(
        name = "The Restless Ghost",
        version = "0.1",
        author = "BotWithUs",
        description = "Solves The Restless Ghost (quest 27) end-to-end.",
        category = ScriptCategory.QUESTING)
public final class RestlessGhostScript extends QuestScript {

    private static final int VARP_TRACKER = 2324;

    private static final int AMULET_GHOSTSPEAK = 552;
    private static final int AMULET_WEAR_OPTION = 2;
    private static final int MUDDY_SKULL = 553;

    public RestlessGhostScript() {
        super(Quests.THE_RESTLESS_GHOST);
    }

    @Override
    protected List<QuestStep> defineSteps() {
        return List.of(
                acceptQuest(),
                getGhostspeakAmulet(),
                consultGhostAndGetSkull(),
                returnSkullToCoffin());
    }

    /** Phase 0 — talk to Father Aereck in Lumbridge church and accept. */
    private QuestStep acceptQuest() {
        return Steps.sequence(
                Steps.walkTo(3243, 3210, 0),
                Steps.talkTo("Father Aereck")
                        .selectOption("I'm looking for a quest!"),
                Steps.acceptQuest()
        )
                .whenVar(VARP_TRACKER, 0)
                .named("Phase 0: accept quest from Father Aereck");
    }

    /** Phase 1 — fetch the ghostspeak amulet from Father Urhney in the swamp. */
    private QuestStep getGhostspeakAmulet() {
        return Steps.sequence(
                Steps.walkTo(3147, 3176, 0),
                Steps.talkTo("Father Urhney")
                        .selectOption("Father Aereck sent me to talk to you.")
                        .selectOption("A ghost is haunting his graveyard.")
        )
                .whenVarInRange(VARP_TRACKER, 1, 4)
                .andCondition(lacksAmulet())
                .named("Phase 1: ghostspeak amulet from Father Urhney");
    }

    /**
     * Phase 2 — open the coffin, wear the amulet, ask the ghost what's wrong,
     * then search the rocks for the muddy skull. One sequence so the
     * coffin&rarr;ghost&rarr;rocks ordering is deterministic and restart-safe.
     */
    private QuestStep consultGhostAndGetSkull() {
        return Steps.sequence(
                Steps.walkTo(3250, 3193, 0),
                Steps.objectInteract("Coffin"),
                Steps.equip(AMULET_GHOSTSPEAK).withOption(AMULET_WEAR_OPTION),
                Steps.talkTo("Restless ghost")
                        .selectOption("Yep. Now, tell me what the problem is."),
                Steps.walkTo(3235, 3148, 0),
                Steps.objectInteract("Rocks")
                        .withAction("Search")
                        .withSuccess(ctx -> ctx.api().backpack().contains(MUDDY_SKULL))
                        .withTimeout(Duration.ofSeconds(30))
        )
                .whenVarInRange(VARP_TRACKER, 1, 4)
                .andCondition(hasAmuletMissingSkull())
                .named("Phase 2: consult ghost, search rocks for muddy skull");
    }

    /** Phase 3 — reopen the coffin and use the muddy skull on it to finish. */
    private QuestStep returnSkullToCoffin() {
        // No trailing waitForVar: a SequenceStep polls child success() against
        // the state snapshot from when the sequence started (never refreshed),
        // so an in-sequence waitForVar stalls. The router re-picks on fresh
        // state — once the quest completes (varp 5) no gate applies and it stops.
        return Steps.sequence(
                Steps.walkTo(3250, 3193, 0),
                Steps.objectInteract("Coffin"),
                Steps.useItemOnObject(MUDDY_SKULL, "Coffin")
        )
                .whenVarInRange(VARP_TRACKER, 1, 4)
                .andBackpackHas(MUDDY_SKULL)
                .named("Phase 3: use muddy skull on coffin");
    }

    /** True while the player has the ghostspeak amulet neither carried nor worn. */
    private static BiPredicate<QuestState, GameAPI> lacksAmulet() {
        return (state, api) -> !api.backpack().contains(AMULET_GHOSTSPEAK)
                && !api.equipment().contains(AMULET_GHOSTSPEAK);
    }

    /** True once the amulet is held or worn but the muddy skull is not yet carried. */
    private static BiPredicate<QuestState, GameAPI> hasAmuletMissingSkull() {
        return (state, api) -> (api.backpack().contains(AMULET_GHOSTSPEAK)
                || api.equipment().contains(AMULET_GHOSTSPEAK))
                && !api.backpack().contains(MUDDY_SKULL);
    }
}
