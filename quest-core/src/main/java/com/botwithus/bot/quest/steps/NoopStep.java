package com.botwithus.bot.quest.steps;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.quest.QuestContext;
import com.botwithus.bot.quest.QuestState;
import com.botwithus.bot.quest.QuestStep;
import com.botwithus.bot.quest.StepResult;

import java.util.function.Predicate;

/**
 * Always-applicable, always-succeeds step. Useful as the {@code otherwise}
 * branch of a {@link DispatchStep} when no action is appropriate.
 */
public final class NoopStep implements QuestStep {

    public NoopStep() {}

    @Override
    public String name() {
        return "noop";
    }

    @Override
    public boolean appliesTo(QuestState state, GameAPI api) {
        return true;
    }

    @Override
    public StepResult execute(QuestContext ctx) {
        return StepResult.done();
    }

    @Override
    public Predicate<QuestContext> success() {
        return ctx -> true;
    }
}
