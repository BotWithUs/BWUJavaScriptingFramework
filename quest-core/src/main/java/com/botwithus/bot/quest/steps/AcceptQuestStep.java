package com.botwithus.bot.quest.steps;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.component.ComponentNode;
import com.botwithus.bot.api.component.Components;
import com.botwithus.bot.quest.QuestContext;
import com.botwithus.bot.quest.QuestDialog;
import com.botwithus.bot.quest.QuestState;
import com.botwithus.bot.quest.QuestStep;
import com.botwithus.bot.quest.StepResult;

import java.time.Duration;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Clicks the "Accept" button / option on a quest offer. Discovery-driven like
 * {@link QuestDialog}: it does not hardcode the offer interface id (RS3 starts
 * quests two ways — a chat "Yes/Accept" option, or a quest-offer scroll with
 * an "Accept Quest" button). The step tries, in order:
 *
 * <ol>
 *   <li>the explicit offer interface(s) set with {@link #viaInterface(int...)},
 *       scanning for an "accept"/"start" label;</li>
 *   <li>chat-option accept via {@link QuestDialog#clickOption} for each label
 *       in {@link #labels} (defaults cover "Accept" + common "Yes…" variants);</li>
 *   <li>a label scan across {@link QuestDialog#HINT_INTERFACES};</li>
 *   <li>advancing any open dialog via {@link QuestDialog#tryContinue}.</li>
 * </ol>
 *
 * <p>This step does not itself confirm the quest started — pair it in a
 * sequence with {@code Steps.waitForVar(progressVar, 1).orGreater()}, which is
 * the authoritative gate. When no offer/dialog is open at all it returns
 * {@link StepResult.Done} (treats "nothing to accept" as already started), so
 * it is safe on a mid-quest restart.</p>
 *
 * <p>The scroll path ({@link #viaInterface}) needs the real offer interface id,
 * which is best pinned with the live Interface Debugger; bolt-questhelper finds
 * that button by texture, not by id, so it can't supply one.</p>
 */
public final class AcceptQuestStep implements QuestStep {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);
    private static final int SETTLE_MS = 700;

    /** Default accept labels, most specific first. */
    private static final String[] DEFAULT_LABELS = {
            "accept quest", "accept", "start quest",
            "yes, i'll help.", "yes please.", "yes.", "yes"
    };

    private int[] offerInterfaces = new int[0];
    private String[] labels = DEFAULT_LABELS;
    private Duration timeout = DEFAULT_TIMEOUT;
    private boolean accepted;

    public AcceptQuestStep() {
    }

    /** Pin explicit quest-offer interface id(s) to scan first. */
    public AcceptQuestStep viaInterface(int... interfaceIds) {
        this.offerInterfaces = interfaceIds.clone();
        return this;
    }

    /** Override the accept-label set (matched case-insensitively, substring). */
    public AcceptQuestStep withLabels(String... acceptLabels) {
        this.labels = acceptLabels.clone();
        return this;
    }

    public AcceptQuestStep withTimeout(Duration t) {
        this.timeout = t;
        return this;
    }

    @Override
    public String name() {
        return "acceptQuest";
    }

    @Override
    public boolean appliesTo(QuestState state, GameAPI api) {
        return true;
    }

    @Override
    public void onEnter(QuestContext ctx) {
        accepted = false;
    }

    @Override
    public StepResult execute(QuestContext ctx) {
        GameAPI api = ctx.api();
        if (clickAcceptIn(api, offerInterfaces)) {
            accepted = true;
            return StepResult.continueIn(SETTLE_MS);
        }
        for (String label : labels) {
            if (QuestDialog.clickOption(api, label)) {
                accepted = true;
                return StepResult.continueIn(SETTLE_MS);
            }
        }
        if (clickAcceptIn(api, QuestDialog.HINT_INTERFACES)) {
            accepted = true;
            return StepResult.continueIn(SETTLE_MS);
        }
        if (QuestDialog.isOpen(api)) {
            QuestDialog.tryContinue(api);
            return StepResult.continueIn(SETTLE_MS);
        }
        // Nothing open to act on: either already accepted, or the offer never
        // appeared. Let the sequence's waitForVar be the real gate.
        return StepResult.done();
    }

    @Override
    public Predicate<QuestContext> success() {
        return ctx -> accepted || !QuestDialog.isOpen(ctx.api());
    }

    @Override
    public Duration timeout() {
        return timeout;
    }

    private boolean clickAcceptIn(GameAPI api, int[] interfaceIds) {
        if (interfaceIds.length == 0) {
            return false;
        }
        Components ui = api.components();
        for (int id : interfaceIds) {
            if (!ui.isOpen(id)) {
                continue;
            }
            ComponentNode match = ui.in(id)
                    .filter(n -> isAcceptLabel(n.text()))
                    .first();
            if (match != null) {
                match.interact(1);
                return true;
            }
        }
        return false;
    }

    private static boolean isAcceptLabel(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        String t = raw.replaceAll("<[^>]+>", " ")
                .toLowerCase(Locale.ROOT).strip().replaceAll("\\s+", " ");
        return t.contains("accept") || t.equals("start") || t.contains("start quest");
    }
}
