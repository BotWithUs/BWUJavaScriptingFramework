package com.botwithus.bot.quest;

import com.botwithus.bot.quest.steps.DispatchStep;
import com.botwithus.bot.quest.steps.KillNpcStep;
import com.botwithus.bot.quest.steps.NoopStep;
import com.botwithus.bot.quest.steps.ObjectInteractStep;
import com.botwithus.bot.quest.steps.PickUpGroundStep;
import com.botwithus.bot.quest.steps.SequenceStep;
import com.botwithus.bot.quest.steps.TalkToStep;
import com.botwithus.bot.quest.steps.UseItemOnNpcStep;
import com.botwithus.bot.quest.steps.UseItemOnObjectStep;
import com.botwithus.bot.quest.steps.WaitForVarStep;
import com.botwithus.bot.quest.steps.WalkToStep;

import java.util.List;
import java.util.function.Function;

/**
 * Fluent entry point for the quest DSL. Each factory returns a concrete
 * {@link QuestStep} (or a builder that is itself a {@code QuestStep}) so
 * scripts compose progression declaratively:
 *
 * <pre>{@code
 * return List.of(
 *     Steps.sequence(
 *         Steps.walkTo(3209, 3215, 0),
 *         Steps.talkTo("Cook").selectOption("What's wrong?")
 *     ).whenVar(2492, 0)
 * );
 * }</pre>
 *
 * <p>The factory methods stay narrow: each returns a builder whose chained
 * configuration methods return the same builder type so the resulting
 * expression is still a {@link QuestStep}.</p>
 */
public final class Steps {

    private Steps() {}

    /** Blocking walk to {@code (x, y, plane)}. */
    public static WalkToStep walkTo(int x, int y, int plane) {
        return new WalkToStep(x, y, plane);
    }

    /** Convenience: walk to {@code (x, y)} on plane 0. */
    public static WalkToStep walkTo(int x, int y) {
        return new WalkToStep(x, y, 0);
    }

    /** Begin a Talk-to interaction with the named NPC; chain {@code .selectOption(...)} per dialog click. */
    public static TalkToStep talkTo(String npcName) {
        return new TalkToStep(npcName);
    }

    /** Interact with a scene object by name. Chain {@code .withAction(...)} to pick a non-default option. */
    public static ObjectInteractStep objectInteract(String objectName) {
        return new ObjectInteractStep(objectName);
    }

    /** Use a backpack item on a scene object — by item id (preferred) + object name. */
    public static UseItemOnObjectStep useItemOnObject(int itemId, String objectName) {
        return new UseItemOnObjectStep(itemId, null, objectName);
    }

    /** Use a backpack item on a scene object — by item name (best-effort) + object name. */
    public static UseItemOnObjectStep useItemOnObject(String itemName, String objectName) {
        return new UseItemOnObjectStep(-1, itemName, objectName);
    }

    /** Use a backpack item on an NPC — by item id. */
    public static UseItemOnNpcStep useItemOnNpc(int itemId, String npcName) {
        return new UseItemOnNpcStep(itemId, null, npcName);
    }

    /** Use a backpack item on an NPC — by item name. */
    public static UseItemOnNpcStep useItemOnNpc(String itemName, String npcName) {
        return new UseItemOnNpcStep(-1, itemName, npcName);
    }

    /** Pick up the nearest ground item with the given id. */
    public static PickUpGroundStep pickUpGround(int itemId) {
        return new PickUpGroundStep(itemId, null);
    }

    /** Pick up the nearest ground item whose name matches. */
    public static PickUpGroundStep pickUpGround(String itemName) {
        return new PickUpGroundStep(-1, itemName);
    }

    /** Block until {@code varId == value}. Used as the terminal step of a phase. */
    public static WaitForVarStep waitForVar(int varId, int value) {
        return new WaitForVarStep(varId, value);
    }

    /** No-op step. Useful as the {@code .otherwise(...)} branch of a dispatch. */
    public static QuestStep noop() {
        return new NoopStep();
    }

    /** Chain children in order; each runs to success before the next starts. */
    public static SequenceStep sequence(QuestStep... children) {
        return new SequenceStep(List.of(children));
    }

    /** Branch on a state-derived selector value. Configure with {@code .on(value, step)}. */
    public static DispatchStep dispatch(Function<QuestState, Integer> selector) {
        return new DispatchStep(selector);
    }

    /** Kill an NPC by name (stub — interacts "Attack"; success on NPC dead). */
    public static KillNpcStep killNpc(String npcName) {
        return new KillNpcStep(npcName);
    }
}
