package com.botwithus.bot.quest.steps;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.entities.Npc;
import com.botwithus.bot.quest.QuestContext;
import com.botwithus.bot.quest.QuestDialog;
import com.botwithus.bot.quest.QuestState;
import com.botwithus.bot.quest.QuestStep;
import com.botwithus.bot.quest.StepResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Multi-click NPC dialog driver. The step opens the conversation with the
 * named NPC (skipped when a dialog is already on screen — useful when the
 * preceding step left one open) and then walks the configured option list,
 * one click per execute call:
 *
 * <pre>{@code
 * Steps.talkTo("Cook")
 *      .selectOption("What's wrong?")
 *      .selectOption("Yes")
 *      .selectOption("I'll get right on it.");
 * }</pre>
 *
 * <p>Each {@link #selectOption(String)} adds one click to the chain. After
 * the last configured click, {@link #execute(QuestContext)} returns
 * {@link StepResult.Done} and {@link #success()} resolves true once the
 * dialog interface closes.</p>
 *
 * <p>Click pointer state is per-step-instance and resets in
 * {@link #onEnter(QuestContext)} so the same step can be re-entered on a
 * later progression cycle.</p>
 */
public final class TalkToStep implements QuestStep {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(45);
    private static final int INTER_CLICK_DELAY_MS = 700;

    private final String npcName;
    private final List<String> dialogOptions = new ArrayList<>();
    private Duration timeout = DEFAULT_TIMEOUT;

    private int clickPointer;
    private boolean greeted;

    public TalkToStep(String npcName) {
        this.npcName = npcName;
    }

    /** Append one click to the dialog chain. Order matters. */
    public TalkToStep selectOption(String optionText) {
        dialogOptions.add(optionText);
        return this;
    }

    public TalkToStep withTimeout(Duration t) {
        this.timeout = t;
        return this;
    }

    @Override
    public String name() {
        return "talkTo(" + npcName + ", " + dialogOptions.size() + " clicks)";
    }

    @Override
    public boolean appliesTo(QuestState state, GameAPI api) {
        return true;
    }

    @Override
    public void onEnter(QuestContext ctx) {
        clickPointer = 0;
        greeted = false;
    }

    @Override
    public StepResult execute(QuestContext ctx) {
        GameAPI api = ctx.api();
        if (!greeted && !QuestDialog.isOpen(api)) {
            Npc npc = api.npcs().nearest(npcName);
            if (npc == null) {
                return StepResult.retry("NPC '" + npcName + "' not in scene");
            }
            if (!npc.interact("Talk-to")) {
                return StepResult.retry("NPC '" + npcName + "' has no Talk-to option");
            }
            greeted = true;
            return StepResult.continueIn(INTER_CLICK_DELAY_MS);
        }
        if (!QuestDialog.isOpen(api)) {
            return clickPointer >= dialogOptions.size()
                    ? StepResult.done()
                    : StepResult.continueIn(INTER_CLICK_DELAY_MS);
        }
        if (clickPointer < dialogOptions.size()) {
            String next = dialogOptions.get(clickPointer);
            if (QuestDialog.clickOption(api, next)) {
                clickPointer++;
            } else {
                QuestDialog.tryContinue(api);
            }
            return StepResult.continueIn(INTER_CLICK_DELAY_MS);
        }
        QuestDialog.tryContinue(api);
        return StepResult.continueIn(INTER_CLICK_DELAY_MS);
    }

    @Override
    public Predicate<QuestContext> success() {
        return ctx -> clickPointer >= dialogOptions.size()
                && !QuestDialog.isOpen(ctx.api());
    }

    @Override
    public Duration timeout() {
        return timeout;
    }
}
